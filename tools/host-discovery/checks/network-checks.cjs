const http=require('http'),https=require('https'),net=require('net'),fs=require('fs');
const host=process.argv[2], output=process.argv[3];
if(!host || !output) throw Error('Usage: node network-checks.cjs HOST OUTPUT.json');
const results=[];
function req(path){return new Promise((resolve,reject)=>{
  const r=http.get({host,port:47989,path,timeout:5000,agent:false},res=>{
    let body=''; res.on('data',b=>body+=b); res.on('end',()=>resolve({status:res.statusCode,body}));
  }); r.on('error',reject);r.on('timeout',()=>r.destroy(Error('timeout')));
});}
function check(ok,message){if(!ok)throw Error(message);results.push(message);console.log(message);}
(async()=>{
  const first=await req('/serverinfo');
  check(first.status===200 && first.body.includes('<hostname>Amaterasu</hostname>'),'HTTP discovery reachable');
  check(first.body.includes('<LocalIP>'+host+'</LocalIP>'),'Actual LAN address advertised');
  check(first.body.includes('<PairStatus>0</PairStatus>'),'Anonymous discovery does not claim a paired identity');
  const id=first.body.match(/<uniqueid>([^<]+)<\/uniqueid>/)[1];
  for(let i=0;i<20;i++)if(!(await req('/serverinfo')).body.includes('<uniqueid>'+id+'</uniqueid>'))throw Error('Identity changed');
  results.push('Stable identity across 20 discovery requests');
  for(const path of ['/applist','/launch?appid=1&corever=1&rikey=00000000000000000000000000000000&rikeyid=1','/resume?appid=1','/cancel']) {
    check((await req(path)).status===404,'Unauthenticated '+path.split('?')[0]+' rejected');
  }
  await new Promise((resolve,reject)=>{
    const r=https.get({host,port:47984,path:'/serverinfo',rejectUnauthorized:false,timeout:4000},res=>{
      res.resume(); reject(Error('TLS accepted a missing client certificate'));
    });
    r.on('error',()=>{results.push('Mutual TLS rejects missing client certificate');resolve();});
    r.on('timeout',()=>{r.destroy();reject(Error('TLS timed out; rejection not established'));});
  });
  await new Promise((resolve,reject)=>{
    let bytes=0;
    const socket=net.connect(48010,host,()=>socket.write('OPTIONS rtsp://'+host+'/ RTSP/1.0\r\nCSeq: 1\r\n\r\n'));
    socket.on('data',b=>bytes+=b.length);
    socket.on('error',e=>{if(e.code!=='ECONNRESET')reject(e);});
    socket.on('close',()=>{try{check(bytes===0,'RTSP without authenticated launch receives no session');resolve();}catch(e){reject(e);}});
    socket.setTimeout(4000,()=>{socket.destroy();reject(Error('RTSP rejection timed out'));});
  });
  fs.writeFileSync(output,JSON.stringify({passed:true,results,hostIdentity:id},null,2));
})().catch(e=>{fs.writeFileSync(output,JSON.stringify({passed:false,results,error:String(e)},null,2));console.error(e);process.exitCode=1;});
