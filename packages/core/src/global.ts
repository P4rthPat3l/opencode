import path from "path"
import fs from "fs/promises"
import { xdgData, xdgCache, xdgConfig, xdgState } from "xdg-basedir"
import os from "os"
import { Context, Effect, Layer } from "effect"
import { Flock } from "./util/flock"
import { Flag } from "./flag/flag"
import { makeGlobalNode } from "./effect/app-node"
import { Product } from "./product"

const app = Product.id
const forkData = `${Product.envPrefix}_DATA_DIR`
const forkCache = `${Product.envPrefix}_CACHE_DIR`
const forkConfig = `${Product.envPrefix}_CONFIG_DIR`
const forkState = `${Product.envPrefix}_STATE_DIR`
const forkLog = `${Product.envPrefix}_LOG_DIR`

function envPath(env: NodeJS.ProcessEnv, key: string, fallback: string) {
  return env[key]?.trim() || fallback
}

export function resolvePaths(env: NodeJS.ProcessEnv = process.env) {
  const data = envPath(env, forkData, path.join(xdgData!, app))
  const cache = envPath(env, forkCache, path.join(xdgCache!, app))
  const config = envPath(env, forkConfig, path.join(xdgConfig!, app))
  const state = envPath(env, forkState, path.join(xdgState!, app))
  const tmp = path.join(os.tmpdir(), envPath(env, `${Product.envPrefix}_TMP_NAME`, app))
  const log = envPath(env, forkLog, path.join(data, "log"))

  return {
    get home() {
      return env.OPENCODE_TEST_HOME ?? os.homedir()
    },
    data,
    bin: path.join(cache, "bin"),
    log,
    repos: path.join(data, "repos"),
    cache,
    config,
    state,
    tmp,
  }
}

const paths = resolvePaths()

export const Path = paths

Flock.setGlobal({ state: Path.state })

await Promise.all([
  fs.mkdir(Path.data, { recursive: true }),
  fs.mkdir(Path.config, { recursive: true }),
  fs.mkdir(Path.state, { recursive: true }),
  fs.mkdir(Path.tmp, { recursive: true }),
  fs.mkdir(Path.log, { recursive: true }),
  fs.mkdir(Path.bin, { recursive: true }),
  fs.mkdir(Path.repos, { recursive: true }),
])

export class Service extends Context.Service<Service, Interface>()("@opencode/Global") {}

export interface Interface {
  readonly home: string
  readonly data: string
  readonly cache: string
  readonly config: string
  readonly state: string
  readonly tmp: string
  readonly bin: string
  readonly log: string
  readonly repos: string
}

export function make(input: Partial<Interface> = {}): Interface {
  return {
    home: Path.home,
    data: Path.data,
    cache: Path.cache,
    config: process.env[forkConfig] ?? Flag.OPENCODE_CONFIG_DIR ?? Path.config,
    state: Path.state,
    tmp: Path.tmp,
    bin: Path.bin,
    log: Path.log,
    repos: Path.repos,
    ...input,
  }
}

const layer = Layer.effect(
  Service,
  Effect.sync(() => Service.of(make())),
)

export const node = makeGlobalNode({ service: Service, layer: layer, deps: [] })

export const layerWith = (input: Partial<Interface>) =>
  Layer.effect(
    Service,
    Effect.sync(() => Service.of(make(input))),
  )

export * as Global from "./global"
