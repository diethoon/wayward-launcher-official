import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
const js=readFileSync(new URL("../app/src/main/assets/imagepack-folder-picker.js",import.meta.url),"utf8");
const java=readFileSync(new URL("../app/src/main/java/com/wayward/launcher/ImagePackDirectoryAccess.java",import.meta.url),"utf8");
const main=readFileSync(new URL("../app/src/main/java/com/wayward/launcher/MainActivity.java",import.meta.url),"utf8");
assert.match(js,/async function\* iterateDirectoryEntries\(/);
for(const prefix of ["__WAYWARD_DIR_START__:","__WAYWARD_DIR_ACK__:","__WAYWARD_DIR_CANCEL__:","__WAYWARD_DIR_RELEASE__:"]) assert.ok(js.includes(prefix));
assert.match(js,/packet\.batchIndex !== state\.expectedBatch/);
assert.match(js,/for await \(const child of iterateDirectoryEntries/);
assert.match(java,/Executors\.newFixedThreadPool\(2\)/);
assert.match(java,/CancellationSignal cancellationSignal = new CancellationSignal\(\)/);
assert.match(java,/task\.cancellationSignal\)/);
assert.match(java,/DIRECTORY_BATCH_SIZE = 32/);
assert.match(java,/task\.attachCursor\(cursor\)/);
assert.match(java,/volatile Cursor activeCursor/);
assert.match(java,/cursor\.close\(\)/);
assert.match(java,/acknowledgement\.await\([\s\S]*TimeUnit\.SECONDS/);
assert.match(java,/cancelAllDirectoryLists\(\)/);
assert.match(main,/listImagePackDirectoryForRequest\(String capability, String documentUri, String requestId\)/);
assert.match(java,/MAX_FILE_BYTES = 32 \* 1024 \* 1024/);
assert.match(java,/MAX_STREAM_CHUNK_BYTES = 64 \* 1024/);
assert.match(java,/MAX_OPEN_STREAMS = 4/);
assert.match(js,/dispatchInputEvent\(input, "input", true\);\s*dispatchInputEvent\(input, "change", true\);/);
assert.match(java,/takePersistableUriPermission\(selected, grants\)/);
const start=js.indexOf("  async function* iterateDirectoryEntries("),end=js.indexOf("  async function enumerateSafTree",start);
assert.ok(start>=0&&end>start);
const iteratorSource=js.slice(start,end),scenarios=new Map(),active=new Map(),observed={acks:[],cancels:[]},window={};
const host={listImagePackDirectoryForRequest(_cap,command,id){
  if(command.startsWith("__WAYWARD_DIR_START__:")){
    const scenario=scenarios.get(command.slice("__WAYWARD_DIR_START__:".length));
    if(!scenario)return JSON.stringify({ok:false,name:"NotFoundError"});
    active.set(id,{scenario,next:0});
    const first = scenario.packetAt ? scenario.packetAt(0) : scenario.packets[0];
    setTimeout(()=>window.__waywardNativeImagePackDirectoryBatch(id,first),0);
    return JSON.stringify({ok:true});
  }
  if(command.startsWith("__WAYWARD_DIR_ACK__:")){
    const index=Number(command.slice("__WAYWARD_DIR_ACK__:".length)),state=active.get(id);
    if(!state||index!==state.next)return JSON.stringify({ok:false,name:"AbortError"});
    observed.acks.push([id,index]);state.next++;
    const packetCount = state.scenario.packetCount ?? state.scenario.packets.length;
    if(state.next<packetCount) {
      const nextPacket = state.scenario.packetAt
        ? state.scenario.packetAt(state.next) : state.scenario.packets[state.next];
      setTimeout(()=>window.__waywardNativeImagePackDirectoryBatch(id,nextPacket),0);
    } else active.delete(id);
    return JSON.stringify({ok:true});
  }
  if(command==="__WAYWARD_DIR_CANCEL__:"){observed.cancels.push(id);active.delete(id);return JSON.stringify({ok:true});}
  return JSON.stringify({ok:true});
}};
const context=vm.createContext({window,host,nativeCapability:"test",pendingDirectoryLists:new Map(),nextDirectoryListId:0,
  Promise,Map,Set,String,Number,Array,Date,Error,setTimeout,clearTimeout,
  domError(n,m){const e=new Error(m);e.name=n;return e;},
  parseResult(raw){const v=typeof raw==="string"?JSON.parse(raw):raw;if(!v||v.ok!==true){const e=new Error(v?.message||"native error");e.name=v?.name||"NotReadableError";throw e;}return v;},
  checkUploadCancelled(c){if(c?.cancelled){const e=new Error("cancelled");e.name="AbortError";throw e;}}
});
vm.runInContext([
  "window.__waywardNativeImagePackDirectoryBatch=function(id,p){const s=pendingDirectoryLists.get(String(id||''));if(!s)return false;if(s.waiter){const r=s.waiter;s.waiter=null;r(p);}else if(!s.packet)s.packet=p;else return false;return true;};",
  iteratorSource,
  "globalThis.makeIterator=(u,id,c)=>iterateDirectoryEntries(u,id,c);"
].join("\n"),context);
const ctl=id=>({requestId:id,cancelled:false,nativeDirectoryIds:new Set()});
const batch=(i,d,e)=>({ok:true,batchIndex:i,done:d,entries:e});
const collect=async it=>{const a=[];for await(const x of it)a.push(x.name);return a;};
scenarios.set("dir://two",{packets:[batch(0,false,[{name:"a"},{name:"b"}]),batch(1,true,[{name:"c"}])]});
scenarios.set("dir://empty",{packets:[batch(0,true,[])]});
scenarios.set("dir://error",{packets:[{ok:false,batchIndex:0,done:true,name:"NotReadableError",message:"provider"}]});
scenarios.set("dir://a",{packets:[batch(0,true,[{name:"only-a"}])]});
scenarios.set("dir://b",{packets:[batch(0,true,[{name:"only-b"}])]});
assert.deepEqual(await collect(context.makeIterator("dir://two","r-two",ctl("r-two"))),["a","b","c"]);
assert.deepEqual(observed.acks.slice(0,2).map(x=>x[1]),[0,1]);
assert.deepEqual(await collect(context.makeIterator("dir://empty","r-empty",ctl("r-empty"))),[]);
await assert.rejects(collect(context.makeIterator("dir://error","r-error",ctl("r-error"))),e=>e.name==="NotReadableError");
assert.deepEqual(await Promise.all([collect(context.makeIterator("dir://a","r-a",ctl("r-a"))),collect(context.makeIterator("dir://b","r-b",ctl("r-b")))]),[["only-a"],["only-b"]]);
const stressCount = 100000, stressBatchSize = 32;
scenarios.set("dir://stress", {
  packetCount: Math.ceil(stressCount / stressBatchSize),
  packetAt(index) {
    const start = index * stressBatchSize;
    const count = Math.min(stressBatchSize, stressCount - start);
    return batch(index, start + count === stressCount,
      Array.from({length:count},(_,offset)=>({name:"file-" + (start + offset)})));
  }
});
const stressNames = await collect(context.makeIterator("dir://stress","r-stress",ctl("r-stress")));
assert.equal(stressNames.length, stressCount, "100k SAF entries are consumed incrementally");
assert.equal(stressNames[0], "file-0");
assert.equal(stressNames[stressNames.length - 1], "file-99999");
const cancel=ctl("r-cancel"),it=context.makeIterator("dir://two","r-cancel",cancel);
assert.equal((await it.next()).value.name,"a");cancel.cancelled=true;
await assert.rejects(it.next(),e=>e.name==="AbortError");assert.ok(observed.cancels.length>0);
console.log("Asynchronous SAF batch protocol simulation passed.");
