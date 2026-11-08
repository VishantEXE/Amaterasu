// Usage: node analyze-scheduling.cjs TRACE.txt THREADS.txt OUTPUT.json
const fs=require('fs'),readline=require('readline');
const [trace,threads,output,coveragePath]=process.argv.slice(2);
let coverage=coveragePath?JSON.parse(fs.readFileSync(coveragePath,'utf8')).common:null;
if(!output)throw Error('Expected trace, thread-list, output paths');
const wanted=new Set(fs.readFileSync(threads,'utf8').split(/\r?\n/).map(x=>x.match(/^\s*\d+\s+(\d+)/)?.[1]).filter(Boolean).map(Number));
const queueOrigin=new Map();
const data=new Map(),runStart=new Map(),queueStart=new Map(),running=new Set();
const item=(pid,name)=>{if(!data.has(pid))data.set(pid,{pid,name,cpuMs:0,dispatchMs:[]});return data.get(pid);};
let first=Infinity,last=-Infinity;
(async()=>{
  if(!coverage){
    const cpus=new Map();
    for await(const line of readline.createInterface({input:fs.createReadStream(trace)})){
      const m=line.match(/\[(\d+)\].*?\s(\d+\.\d+): sched_/);if(!m)continue;
      const cpu=+m[1],t=+m[2],prior=cpus.get(cpu)||{first:t,last:t};prior.first=Math.min(prior.first,t);prior.last=Math.max(prior.last,t);cpus.set(cpu,prior);
    }
    coverage={start:Math.max(...[...cpus.values()].map(x=>x.first)),end:Math.min(...[...cpus.values()].map(x=>x.last))};
    if(!(coverage.end>coverage.start))throw Error('No common per-CPU trace window');
  }
  for await(const line of readline.createInterface({input:fs.createReadStream(trace)})) {
    const event=line.match(/\s(\d+\.\d+): sched_(switch|wakeup):\s*(.*)$/);if(!event)continue;
    const t=Number(event[1]);if(coverage&&(t<coverage.start||t>coverage.end))continue;first=Math.min(first,t);last=Math.max(last,t);
    if(event[2]==='wakeup') {
      const m=event[3].match(/comm=(.*?) pid=(\d+) /);if(!m)continue;const pid=+m[2];
      if(wanted.has(pid)){item(pid,m[1]);if(!running.has(pid)&&!queueStart.has(pid)){queueStart.set(pid,t);queueOrigin.set(pid,line);}}
    } else {
      const m=event[3].match(/prev_comm=(.*?) prev_pid=(\d+).*?prev_state=(\S+) ==> next_comm=(.*?) next_pid=(\d+)/);if(!m)continue;
      const prev=+m[2],next=+m[5];
      if(wanted.has(prev)) {
        const d=item(prev,m[1]);if(runStart.has(prev))d.cpuMs+=(t-runStart.get(prev))*1000;
        runStart.delete(prev);running.delete(prev);
        if(m[3].startsWith('R')){queueStart.set(prev,t);queueOrigin.set(prev,line);}else {queueStart.delete(prev);queueOrigin.delete(prev);}
      }
      if(wanted.has(next)) {
        const d=item(next,m[4]);if(queueStart.has(next)){const delay=(t-queueStart.get(next))*1000;d.dispatchMs.push(delay);if(!d.maxCase||delay>d.maxCase.ms)d.maxCase={ms:delay,from:queueOrigin.get(next),to:line};}
        queueStart.delete(next);runStart.set(next,t);running.add(next);
      }
    }
  }
  const stats=a=>{a.sort((a,b)=>a-b);const p=x=>a[Math.floor((a.length-1)*x)];return {n:a.length,p50:p(.5),p95:p(.95),p99:p(.99),max:a.at(-1)};};
  const result={coverage,traceSeconds:last-first,note:'Short instrumented window; restrict to common per-CPU time coverage when supplied. Dispatch delay is runnable/preempted-to-scheduled time, not GPU or network latency.',threads:[...data.values()].map(d=>({pid:d.pid,name:d.name,cpuMs:d.cpuMs,dispatchMs:stats(d.dispatchMs),maxCase:d.maxCase})).sort((a,b)=>b.cpuMs-a.cpuMs)};
  fs.writeFileSync(output,JSON.stringify(result,null,2));console.log(JSON.stringify({...result,threads:result.threads.slice(0,8)},null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
