const fs = require('node:fs');
const path = require('node:path');
const folder = process.argv[2];
if (!folder) throw new Error('Usage: node analyze-diagnostics.cjs CAPTURE_FOLDER');
const clientLabel = process.argv[3] || 'quest';
const read = name => fs.existsSync(path.join(folder, name)) ? fs.readFileSync(path.join(folder, name), 'utf8').replace(/^\uFEFF/, '') : '';
function stats(values) {
  values = values.filter(Number.isFinite).sort((a, b) => a - b);
  if (!values.length) return null;
  const q = p => +values[Math.floor((values.length - 1) * p)].toFixed(3);
  return { n: values.length, mean: +(values.reduce((a, b) => a + b, 0) / values.length).toFixed(3), min: q(0), p50: q(.5), p95: q(.95), p99: q(.99), max: q(1) };
}
const result = { units: 'Timing in milliseconds; memory in MiB. Overlay samples are unweighted observations, not an end-to-end latency measurement.' };
for (const label of ['host', clientLabel]) {
  const samples = fs.readdirSync(folder).filter(n => new RegExp(`^${label}-\\d+-memory\\.txt$`).test(n)).sort();
  const fields = { pssMiB: /TOTAL PSS:\s+(\d+)/, rssMiB: /TOTAL RSS:\s+(\d+)/, graphicsMiB: /Graphics:\s+(\d+)/, javaMiB: /Java Heap:\s+(\d+)/, nativeMiB: /Native Heap:\s+(\d+)/ };
  result[label + 'Memory'] = Object.fromEntries(Object.entries(fields).map(([key, pattern]) => [key, stats(samples.map(n => { const m = read(n).match(pattern); return m ? +m[1] / 1024 : NaN; }))]));
  result[label + 'Memory'].first = samples[0];
  result[label + 'Memory'].last = samples.at(-1);
  const wifi = fs.readdirSync(folder).filter(n => new RegExp(`^${label}-\\d+-wifi\\.txt$`).test(n)).sort().map(read);
  result[label + 'Wifi'] = {};
  for (const [key, pattern] of Object.entries({ rssiDbm: /RSSI: (-?\d+)/, txLinkMbps: /Tx Link speed: (\d+)/, retriesPerSecond: /retriedTxPacketsPerSecond: ([\d.]+)/, lostTxPerSecond: /lostTxPacketsPerSecond: ([\d.]+)/ })) {
    result[label + 'Wifi'][key] = stats(wifi.map(s => { const m = s.match(pattern); return m ? +m[1] : NaN; }));
  }
}
const host = [...new Set((read('host-pre-capture.log')+'\n'+read('host-live.log')).split(/\r?\n/))].join('\n');
const captureYear = read('capture-start-utc.txt').slice(0,4);
const utcOffset = read('capture-utc-offset.txt').trim() || '-03:00';
function wallTime(line) {
  const m=line.match(/^(\d\d-\d\d) (\d\d:\d\d:\d\d\.\d+)/);
  return m ? Date.parse(`${captureYear}-${m[1]}T${m[2]}${utcOffset}`) : NaN;
}
result.profiles = [...new Set([...host.matchAll(/Live MediaProjection started: ([^\r\n]+)/g)].map(m => m[1]))];
const profileEvents = [...host.matchAll(/^\d+-\d+ \S+\s+(\d+)\s+\d+ I QuestHeldHost: Live MediaProjection started: ([^\r\n]+)/gm)];
result.sessions = profileEvents.map((m,index) => {
  const pid=+m[1], profile=m[2];
  const startMs=wallTime(m[0]);
  let endMs=index+1<profileEvents.length ? wallTime(profileEvents[index+1][0]) : Infinity;
  const ended=host.split(/\r?\n/).find(l=>new RegExp(`\\s${pid}\\s+\\d+ I QuestHeldHost: Live ended`).test(l) && wallTime(l)>startMs && wallTime(l)<endMs);
  if(ended) endMs=wallTime(ended);
  const files=fs.readdirSync(folder).filter(n => {
    const sample=n.match(/^host-(\d+)-memory\.txt$/);
    if(!sample || !read(n).includes(`pid ${pid} `)) return false;
    const time=Date.parse(read(`sample-${+sample[1]}-utc.txt`).trim());
    return time>=startMs && time<endMs;
  });
  const logs=host.split(/\r?\n/).filter(l => new RegExp(`\\s${pid}\\s+\\d+ [A-Z] QuestHeldHost:`).test(l) && wallTime(l)>=startMs && wallTime(l)<endMs).join('\n');
  const report=[...logs.matchAll(/Live sent videoFrames=(\d+) audioPackets=(\d+) audioQueueDrops=(\d+) oversizedFrames=(\d+) lateFrames=(\d+) videoQueueDrops=(\d+)/g)].at(-1);
  return {pid, profile, startUtc:new Date(startMs).toISOString(), endMs:Number.isFinite(endMs)?endMs:null, hostPssMiB:stats(files.map(n => +(read(n).match(/TOTAL PSS:\s+(\d+)/)?.[1] ?? NaN)/1024)), hostGraphicsMiB:stats(files.map(n => +(read(n).match(/Graphics:\s+(\d+)/)?.[1] ?? NaN)/1024)), counters:report ? Object.fromEntries(['sentVideo','sentAudio','audioQueueDrops','oversizedFrames','lateFrames','videoQueueDrops'].map((k,i)=>[k,+report[i+1]])):null};
});
const reports = [...host.matchAll(/Live sent videoFrames=(\d+) audioPackets=(\d+) audioQueueDrops=(\d+) oversizedFrames=(\d+) lateFrames=(\d+) videoQueueDrops=(\d+)/g)];
result.hostLastReport = reports.length ? Object.fromEntries(['sentVideo', 'sentAudio', 'audioQueueDrops', 'oversizedFrames', 'lateFrames', 'videoQueueDrops'].map((key, i) => [key, +reports.at(-1)[i + 1]])) : null;
const measuredStart=Date.parse(read('capture-start-utc.txt').trim());
const measuredEnd=Date.parse(read('capture-end-utc.txt').trim()) || Infinity;
const withinWindow=line=>wallTime(line)>=measuredStart && wallTime(line)<=measuredEnd;
const hostPids=new Set(profileEvents.map(m=>+m[1]));
const fromHost=line=>{const m=line.match(/^\d+-\d+ \S+\s+(\d+)\s+\d+ /);return m && hostPids.has(+m[1]);};
result.congestionAdjustments=host.split(/\r?\n/).filter(l=>withinWindow(l)&&/Reduced encoder bitrate/.test(l));
result.hostErrors = host.split(/\r?\n/).filter(l => withinWindow(l) && fromHost(l) && /Dropped oversized|Live .*failed|Exception|termination reason/.test(l));
const quest = [...new Set((read(clientLabel+'-pre-capture.log')+'\n'+read(clientLabel+'-live.log')).split(/\r?\n/))].join('\n');
const overlays = quest.split(/\r?\n/).filter(l => l.includes('Perf overlay:'));
for (const session of result.sessions) {
  const resolution=session.profile.match(/\d+x\d+/)?.[0];
  const rows=overlays.filter(l=>l.includes(`Video stream: ${resolution} `) && wallTime(l)>=Date.parse(session.startUtc) && (session.endMs===null || wallTime(l)<session.endMs));
  session.receiver={};
  for(const [key,pattern] of Object.entries({incomingFps:/Incoming frame rate from network: ([\d.]+)/,networkMs:/Average network latency: ([\d.]+)/,decodeMs:/Average decoding time: ([\d.]+)/,lossPercent:/Frames dropped by your network connection: ([\d.]+)/})) session.receiver[key]=stats(rows.map(l=>+(l.match(pattern)?.[1]??NaN)));
  session.receiver.audioUnderrunsLast=+(rows.at(-1)?.match(/Audio underruns: (\d+)/)?.[1]??NaN);
}
result.receiver = {};
for (const [key, pattern] of Object.entries({ incomingFps: /Incoming frame rate from network: ([\d.]+)/, renderedFps: /Rendering frame rate: ([\d.]+)/, lossPercent: /Frames dropped by your network connection: ([\d.]+)/, networkMs: /Average network latency: ([\d.]+)/, decodeMs: /Average decoding time: ([\d.]+)/ })) {
  result.receiver[key] = stats(overlays.map(l => { const m = l.match(pattern); return m ? +m[1] : NaN; }));
}
result.receiver.audioUnderrunsLast = overlays.at(-1)?.match(/Audio underruns: (\d+)/)?.[1];
result.receiver.errors = quest.split(/\r?\n/).filter(l => withinWindow(l) && /terminated|handshake failed|connection failed|No video|No audio|AndroidRuntime.*FATAL/.test(l));
const timingDir = ['host-timing', 'timing-snapshot'].map(d => path.join(folder, d)).find(d => fs.existsSync(d));
if (timingDir) {
  function csvFiles(dir) { return fs.readdirSync(dir, {withFileTypes:true}).flatMap(e => e.isDirectory() ? csvFiles(path.join(dir,e.name)) : e.name.endsWith('.csv') ? [path.join(dir,e.name)] : []); }
  const uniqueFiles = new Map();
  for (const f of csvFiles(timingDir)) {
    const key=path.basename(f), prior=uniqueFiles.get(key);
    if(!prior || fs.statSync(f).size>fs.statSync(prior).size) uniqueFiles.set(key,f);
  }
  const files = [...uniqueFiles.values()].sort((a,b)=>path.basename(a).localeCompare(path.basename(b)));
  result.timings=[];
  const windowStart=Date.parse(read('capture-start-utc.txt').trim());
  const starts=result.sessions.map(x=>Date.parse(x.startUtc)).filter(Number.isFinite);
  const startMs=Math.min(windowStart,...starts);
  for (const file of files.filter(f=>+path.basename(f).match(/session-(\d+)/)?.[1]>=startMs-5000)) {
    const text = fs.readFileSync(file, 'utf8');
    const events = {}, submitted = new Map(), encoded = new Map(), latched = new Map(), sending = new Map();
    const render = [], encode = [], send = [], age = [], sourceAge = [], sizes = [], sourceIntervals = [], submitIntervals = [], sendIntervals = [];
    let first, last, prevSource, prevSubmit, prevSend;
    for (const line of text.split('\n')) {
      if (!line || line.startsWith('#') || line.startsWith('event,')) continue;
      const [event, idString, ptsString, tString, bytesString, detailString] = line.split(',');
      if (!detailString) continue; // Ignore an incomplete row while a live file is being copied.
      const id=+idString, pts=+ptsString, t=+tString, bytes=+bytesString, detail=+detailString;
      first ??= t; last = t;
      events[event] = (events[event] || 0) + 1;
      if (event === 'source_latched') { latched.set(id,t); sourceAge.push((t/1000-pts)/1000); if(prevSource !== undefined) sourceIntervals.push((pts-prevSource)/1000); prevSource=pts; }
      if (event === 'capture_submitted') { if(latched.has(id)) render.push((t-latched.get(id))/1e6); submitted.set(pts,t); if(prevSubmit !== undefined) submitIntervals.push((t-prevSubmit)/1e6); prevSubmit=t; }
      if (event === 'encoded') { encoded.set(id,t); sizes.push(bytes); if(submitted.has(pts)) encode.push((t-submitted.get(pts))/1e6); }
      if (event === 'send_start') { sending.set(id,t); age.push((t/1000-pts)/1000); if(prevSend !== undefined) sendIntervals.push((t-prevSend)/1e6); prevSend=t; }
      if (event === 'send_end' && sending.has(id)) send.push((t-sending.get(id))/1e6);
    }
    result.timing = { file: path.relative(folder,file), durationSeconds: (last-first)/1e9, events, sourceAgeAtLatchMs:stats(sourceAge), renderAndSwapMs: stats(render), submittedToEncodedMs: stats(encode), packetizeAndSendMs: stats(send), sampledFrameAgeAtSendMs:stats(age), encodedBytes:stats(sizes), sourceIntervalMs:stats(sourceIntervals), submitIntervalMs:stats(submitIntervals), sendIntervalMs:stats(sendIntervals), truncatedAtCap: text.includes('diagnostic_rows_dropped=') ? text.match(/diagnostic_rows_dropped=(\d+)/)?.[1] : 'Live file: footer not yet written' };
    result.timings.push(result.timing);
  }
}
const start = read('capture-start-utc.txt').trim(), end = read('capture-end-utc.txt').trim();
result.window = {start, end, seconds: start && end ? (Date.parse(end)-Date.parse(start))/1000 : null};
const output = JSON.stringify(result,null,2);
fs.writeFileSync(path.join(folder,'analysis.json'),output+'\n');
console.log(output);
