import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
const source=await readFile(new URL('../src/index.js',import.meta.url),'utf8');
const {default:worker}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const env={GITHUB_TOKEN:'synthetic-test-token',GITHUB_REPO:'test/telemetry'};
async function ingest(payload) {
  let saved;
  const old=globalThis.fetch;
  globalThis.fetch=async(url,opts)=>{
    assert.match(url,/^https:\/\/api\.github\.com\/repos\/test\/telemetry\/contents\//);
    saved=JSON.parse(Buffer.from(JSON.parse(opts.body).content,'base64').toString('utf8'));
    return new Response('{}',{status:201});
  };
  try {
    const response=await worker.fetch(new Request('https://relay.invalid/v1/telemetry',{method:'POST',body:JSON.stringify(payload)}),env);
    return {status:response.status,reply:await response.json(),saved};
  } finally {globalThis.fetch=old;}
}
function sample() {
 return {schema:'smartvowificall.telemetry.v3',logId:'SVW-20261126-AB12CD',installId:'00000000-0000-4000-8000-000000000000',app:{},device:{},service:{},authPackets:[],authBatch:{id:'SVW-20261126-AB12CD',index:1,count:1,recordCount:0,totalRecordCount:0}};
}
test('v3 retains all packets and long nonprivate values; masks only identity/secret portions',async()=>{
 const p=sample();
 p.authPackets=Array.from({length:180},(_,i)=>({sequence:i,profile:'0@nai.epc/ims',identity:'0234100000000001@nai.epc.mnc010.mcc234.3gppnetwork.org',username:'234100000000001@ims.mnc010.mcc234.3gppnetwork.org',MSK:'synthetic-secret',algorithm:'HMAC-SHA-256'}));
 p.authPackets[0].publicPayload='a'.repeat(20000);
 p.authBatch.recordCount=p.authBatch.totalRecordCount=180;
 const r=await ingest(p);assert.equal(r.status,200);assert.equal(r.saved.payload.authPackets.length,180);
 const row=r.saved.payload.authPackets[0];assert.equal(row.publicPayload.length,20000);
 assert.equal(row.identity,'0[Hidden]@nai.epc.mnc010.mcc234.3gppnetwork.org');
 assert.equal(row.username,'[Hidden]@ims.mnc010.mcc234.3gppnetwork.org');
 assert.equal(row.profile,'0@nai.epc/ims');assert.equal(row.MSK,'[Hidden]');assert.equal(row.algorithm,'HMAC-SHA-256');
 assert.equal(r.reply.authRecordCount,180);assert.equal(r.reply.authPartIndex,1);
});
test('invalid batch count rejected before storage',async()=>{
 const p=sample();p.authBatch.recordCount=1;const r=await ingest(p);assert.equal(r.status,400);assert.equal(r.saved,undefined);
});
test('oversized request rejected explicitly',async()=>{
 const p=sample();p.rawLogs='a'.repeat(513*1024);const r=await ingest(p);assert.equal(r.status,413);assert.equal(r.saved,undefined);
});
test('actual Java-produced multipart payloads survive Worker ingestion intact',async(t)=>{
 if(!process.env.TELEMETRY_BATCH_FIXTURE) {t.skip('set TELEMETRY_BATCH_FIXTURE to Java test output');return;}
 const parts=JSON.parse(await readFile(process.env.TELEMETRY_BATCH_FIXTURE,'utf8'));
 const seen=new Set();
 for(const text of parts) {
  const p=JSON.parse(text),r=await ingest(p);assert.equal(r.status,200);
  assert.deepEqual(r.saved.payload.authPackets,p.authPackets);
  for(const row of r.saved.payload.authPackets) {assert(!seen.has(row.sequence));seen.add(row.sequence);}
  assert.equal(r.reply.authRecordCount,p.authBatch.recordCount);
 }
 assert.equal(seen.size,260);
});
