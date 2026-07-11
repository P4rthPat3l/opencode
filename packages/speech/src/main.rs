use miniaudio::{Decoder, DecoderConfig, Device, DeviceConfig, DeviceType, Format, FramesMut};
use std::env;
use std::error::Error;
use std::io::{self, BufRead, Write};
use std::path::Path;
use std::sync::{Arc, Mutex};
use whisper_rs::{FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters};

const SAMPLE_RATE: u32 = 16_000;
const MAX_RECORDING_SAMPLES: usize = SAMPLE_RATE as usize * 60 * 10;

fn main() {
    if let Err(error) = run() {
        eprintln!("{error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), Box<dyn Error>> {
    let mut args = env::args().skip(1);
    match args.next().as_deref() {
        Some("record") => record(required_arg(&mut args, "output WAV path")?),
        Some("transcribe") => transcribe(
            required_arg(&mut args, "model path")?,
            required_arg(&mut args, "audio path")?,
            args.next(),
        ),
        Some("--version") | Some("version") => {
            println!(env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        _ => Err(
            "usage: opencode-speech record <output.wav> | transcribe <model> <audio> [language]"
                .into(),
        ),
    }
}

fn required_arg(
    args: &mut impl Iterator<Item = String>,
    name: &str,
) -> Result<String, Box<dyn Error>> {
    args.next().ok_or_else(|| format!("missing {name}").into())
}

fn record(output: String) -> Result<(), Box<dyn Error>> {
    let samples = Arc::new(Mutex::new(Vec::<f32>::with_capacity(
        SAMPLE_RATE as usize * 30,
    )));
    let capture = Arc::clone(&samples);
    let mut config = DeviceConfig::new(DeviceType::Capture);
    config.set_sample_rate(SAMPLE_RATE);
    config.capture_mut().set_format(Format::F32);
    config.capture_mut().set_channels(1);
    config.set_data_callback(move |_, _, input| {
        let Ok(mut samples) = capture.lock() else {
            return;
        };
        if samples.len() >= MAX_RECORDING_SAMPLES {
            return;
        }
        let input = input.as_samples::<f32>();
        let remaining = MAX_RECORDING_SAMPLES - samples.len();
        samples.extend_from_slice(&input[..input.len().min(remaining)]);
    });

    let device =
        Device::new(None, &config).map_err(|error| format!("microphone unavailable: {error}"))?;
    device
        .start()
        .map_err(|error| format!("failed to start microphone: {error}"))?;
    println!("ready");
    io::stdout().flush()?;
    io::stdin().lock().lines().next();
    device
        .stop()
        .map_err(|error| format!("failed to stop microphone: {error}"))?;

    let samples = samples.lock().map_err(|_| "recording buffer failed")?;
    let spec = hound::WavSpec {
        channels: 1,
        sample_rate: SAMPLE_RATE,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };
    let mut writer = hound::WavWriter::create(output, spec)?;
    for sample in samples.iter() {
        writer.write_sample((sample.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)?;
    }
    writer.finalize()?;
    Ok(())
}

fn transcribe(
    model: String,
    audio: String,
    language: Option<String>,
) -> Result<(), Box<dyn Error>> {
    let samples = decode_audio(Path::new(&audio))?;
    if samples.len() < SAMPLE_RATE as usize / 4 {
        return Err("no audio was recorded".into());
    }
    let rms =
        (samples.iter().map(|sample| sample * sample).sum::<f32>() / samples.len() as f32).sqrt();
    if rms < 0.003 {
        println!();
        return Ok(());
    }

    let context = WhisperContext::new_with_params(&model, WhisperContextParameters::default())?;
    let mut state = context.create_state()?;
    let mut params = FullParams::new(SamplingStrategy::Greedy { best_of: 1 });
    params.set_n_threads(
        std::thread::available_parallelism()
            .map(|threads| threads.get().min(8) as i32)
            .unwrap_or(1),
    );
    params.set_language(language.as_deref().filter(|value| *value != "auto"));
    params.set_no_context(true);
    params.set_no_timestamps(true);
    params.set_print_progress(false);
    params.set_print_realtime(false);
    params.set_print_special(false);
    params.set_print_timestamps(false);
    params.set_suppress_blank(true);
    state.full(params, &samples)?;

    let text = state
        .as_iter()
        .filter(|segment| segment.no_speech_probability() < 0.6)
        .map(|segment| segment.to_string())
        .filter(|segment| !is_silence_marker(segment.trim()))
        .collect::<Vec<_>>()
        .join(" ")
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    println!("{text}");
    Ok(())
}

fn is_silence_marker(text: &str) -> bool {
    (text.starts_with('[') && text.ends_with(']')) || text.eq_ignore_ascii_case("(silence)")
}

fn decode_audio(path: &Path) -> Result<Vec<f32>, Box<dyn Error>> {
    let config = DecoderConfig::new(Format::F32, 1, SAMPLE_RATE);
    let mut decoder = Decoder::from_file(path, Some(&config))
        .map_err(|error| format!("invalid audio: {error}"))?;
    let length = decoder.length_in_pcm_frames() as usize;
    let mut samples = vec![0.0f32; length];
    let mut frames = FramesMut::wrap(&mut samples, Format::F32, 1);
    let read = decoder.read_pcm_frames(&mut frames) as usize;
    samples.truncate(read);
    Ok(samples)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_and_resamples_wav() {
        let path = env::temp_dir().join(format!("opencode-speech-{}.wav", std::process::id()));
        let spec = hound::WavSpec {
            channels: 2,
            sample_rate: 48_000,
            bits_per_sample: 16,
            sample_format: hound::SampleFormat::Int,
        };
        let mut writer = hound::WavWriter::create(&path, spec).unwrap();
        for _ in 0..48_000 {
            writer.write_sample(1_000i16).unwrap();
            writer.write_sample(1_000i16).unwrap();
        }
        writer.finalize().unwrap();

        let samples = decode_audio(&path).unwrap();
        std::fs::remove_file(path).unwrap();

        assert!((15_900..=16_100).contains(&samples.len()));
    }

    #[test]
    fn removes_whisper_silence_markers() {
        assert!(is_silence_marker("[BLANK_AUDIO]"));
        assert!(is_silence_marker("[Silence]"));
        assert!(is_silence_marker("[XBOX SOUND]"));
        assert!(!is_silence_marker("hello"));
    }
}
