#!/usr/bin/env node
/* Render the WebAudio bells to 16-bit mono WAV files for native playback.
   Params mirror BELLS in www/index.html so the native bell matches the web bell. */
const fs = require('fs');
const path = require('path');

const SR = 44100;
const BELLS = {
  bowl:    { base: 210, partials:[1,2.7,4.2,5.4],     decay: 6.0,  type:'sine'     },
  temple:  { base: 380, partials:[1,2.4,3.9,6.1],     decay: 4.2,  type:'sine'     },
  chime:   { base: 700, partials:[1,2.76,5.4],        decay: 3.0,  type:'sine'     },
  gong:    { base: 120, partials:[1,1.5,2.3,3.6,5.1], decay: 8.0,  type:'sine'     },
  crystal: { base: 520, partials:[1,2.01,3.0],        decay: 5.5,  type:'sine'     },
  wood:    { base: 800, partials:[1,3.1],             decay: 0.28, type:'triangle' },
};

function wave(type, ph) {
  if (type === 'triangle') return (2 / Math.PI) * Math.asin(Math.sin(ph));
  return Math.sin(ph);
}

function render(b) {
  // longest partial decay + tail
  const maxDec = Math.max(...b.partials.map((_, i) => b.decay / (1 + i * 0.5)));
  const dur = maxDec + 0.15;
  const n = Math.ceil(dur * SR);
  const buf = new Float64Array(n);
  const attack = 0.008;
  b.partials.forEach((mult, i) => {
    const f = b.base * mult;
    const amp = 0.5 / (i + 1);
    const dec = b.decay / (1 + i * 0.5);
    const w = 2 * Math.PI * f / SR;
    for (let s = 0; s < n; s++) {
      const t = s / SR;
      let env;
      if (t < attack) env = amp * (t / attack);
      else {
        // exponential ramp amp -> 0.0001 over dec (matches exponentialRampToValueAtTime)
        const k = Math.min(1, (t - attack) / dec);
        env = amp * Math.pow(0.0001 / amp, k);
      }
      buf[s] += env * wave(b.type, w * s);
    }
  });
  // normalize to peak 0.9
  let peak = 0;
  for (let s = 0; s < n; s++) peak = Math.max(peak, Math.abs(buf[s]));
  const g = peak > 0 ? 0.9 / peak : 1;
  const pcm = Buffer.alloc(n * 2);
  for (let s = 0; s < n; s++) {
    let v = Math.round(buf[s] * g * 32767);
    v = Math.max(-32768, Math.min(32767, v));
    pcm.writeInt16LE(v, s * 2);
  }
  return pcm;
}

function wav(pcm) {
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);      // PCM
  header.writeUInt16LE(1, 22);      // mono
  header.writeUInt32LE(SR, 24);
  header.writeUInt32LE(SR * 2, 28); // byte rate
  header.writeUInt16LE(2, 32);      // block align
  header.writeUInt16LE(16, 34);     // bits
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}

const outDir = path.join(__dirname, '..', 'android', 'app', 'src', 'main', 'res', 'raw');
fs.mkdirSync(outDir, { recursive: true });
for (const [key, b] of Object.entries(BELLS)) {
  const file = path.join(outDir, `bell_${key}.wav`);
  const data = wav(render(b));
  fs.writeFileSync(file, data);
  console.log(`${key.padEnd(8)} -> ${file}  (${(data.length / 1024).toFixed(0)} KB)`);
}
console.log('done');
