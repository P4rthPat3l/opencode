#!/usr/bin/env bun

import fs from "fs"
import path from "path"

const root = path.resolve(import.meta.dir, "..")
const dist = path.join(root, "dist")
const targets = fs
  .readdirSync(dist, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && entry.name.startsWith("opencode-"))
  .map((entry) => entry.name)
  .filter((entry) => fs.existsSync(path.join(dist, entry, "bin")))
  .sort()

if (targets.length === 0) {
  throw new Error("No opencode dist targets found. Run `bun run build` first.")
}

for (const target of targets) {
  const bin = path.join(dist, target, "bin")
  if (target.includes("linux")) {
    await Bun.$`tar -czf ${path.join(dist, `${target}.tar.gz`)} *`.cwd(bin)
    continue
  }
  await Bun.$`jar --create --file ${path.join(dist, `${target}.zip`)} --no-manifest -C ${bin} .`
}

console.log(`Packaged release archives in ${dist}`)
