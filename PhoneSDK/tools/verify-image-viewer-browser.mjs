import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import { pathToFileURL, fileURLToPath } from 'node:url';
const root = process.env.IMAGE_VIEWER_REPO ?? fileURLToPath(new URL('../../', import.meta.url)).replace(/\/$/, '');
const locale = process.env.IMAGE_VIEWER_LOCALE ?? 'zh-CN';
// The system locale is configurable; the plugin UI is always English.
const wording = (_zh, en) => en;
const moduleName = process.env.PLAYWRIGHT_MODULE ?? 'playwright';
const { chromium } = await import(moduleName.startsWith('/') ? pathToFileURL(moduleName) : moduleName);
const artifacts = await mkdtemp(join(tmpdir(),'pixel-viewer-browser-'));
const binary = join(artifacts,'native');
execFileSync('cc',['-std=c11','-Wall','-Wextra','-Werror','-DIMAGE_VIEWER_PIPE','-I',`${root}/GlassSDK/include`,`${root}/GlassSDK/tests/test_image_viewer.c`,'-o',binary,...(process.env.LZ4_LINK_FLAGS ?? '-llz4').split(/\s+/)]);
const device = spawn(binary,[join(artifacts,'frame.gray4')],{stdio:['pipe','pipe','inherit']});
let pending, buffer=Buffer.alloc(0);
device.stdout.on('data', data=>{buffer=Buffer.concat([buffer,data]); if(buffer.length>=20&&pending){const p=pending; pending=null; p(buffer.subarray(0,20)); buffer=buffer.subarray(20);}});
const server = spawn('node',[`${root}/PhoneSDK/tools/run-browser-studio.mjs`,'--plugin',process.env.IMAGE_VIEWER_PLUGIN ?? `${root}/PhoneSDK/examples/image-viewer`,'--port','4187'],{stdio:['ignore','pipe','pipe']});
await new Promise((resolve,reject)=>{server.stdout.once('data',resolve);server.once('error',reject);server.once('exit',code=>reject(new Error(`server exit ${code}`)));});
let browser;
try {
  browser = await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE,args:['--no-sandbox']});
  const page = await browser.newPage({viewport:{width:1440,height:1000},deviceScaleFactor:3,locale});
  const transfers=[]; let activeTransfer, holdChunk, releaseChunk;
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.exposeFunction('__nativeImageMessage', async bytes=>{
    const data=Buffer.from(bytes),size=Buffer.alloc(4);size.writeUInt32LE(data.length);
    if (data[1]===3 && holdChunk) {
      const reached=holdChunk; holdChunk=null;
      await new Promise(resolve=>{releaseChunk=resolve; reached();});
    }
    if(data[1]===2 || data[1]===5) {
      activeTransfer={lz4:data[1]===5,bytes:0,chunks:0,hash:data.readUInt32LE(16)};transfers.push(activeTransfer);
      if(data[1]===5) {assert.equal(data.readUInt32LE(24),105000);activeTransfer.expected=data.readUInt32LE(20);}
    }
    if(data[1]===3) {activeTransfer.bytes+=data.length-12;activeTransfer.chunks++;}
    return await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error('native ACK timeout')),3000);
      pending=ack=>{clearTimeout(timer);resolve([...ack]);};
      device.stdin.write(Buffer.concat([size,data]));
    });
  });
  // Test-only adapter: public Browser Studio still owns permission/file/lifecycle
  // behavior; the actual C receiver handles the custom image channel.
  await page.route('**/studio.js',async route=>{
    const response=await route.fetch();
    let body=await response.text();
    body=body.replace('function subscribeRuntime() {', 'function subscribeRuntime() { globalThis.imageTestRuntime=runtime; const dispatch=runtime.dispatch.bind(runtime);runtime.dispatch=async(method,params)=>{globalThis.imageMethodCounts??={};globalThis.imageMethodCounts[method]=(globalThis.imageMethodCounts[method]??0)+1;if(method==="runtime.ready"&&globalThis.imageFailReady){globalThis.imageFailReady=false;throw Object.assign(new Error("temporary ready failure"),{code:"INTERNAL_ERROR"});}if(method==="device.subscribeEvents"&&globalThis.imageFailSubscribe)throw new Error("device temporarily unavailable");const result=await dispatch(method,params);if(method==="runtime.getCapabilities")delete result.methods;return result;}; runtime.pluginMessageHandler=async ({data})=>{const ack=await globalThis.__nativeImageMessage([...data]);runtime.emitPluginMessage(18775,Uint8Array.from(ack));};');
    await route.fulfill({response,body});
  });
  await page.goto('http://127.0.0.1:4187');
  await page.locator('dialog').waitFor();
  for(const box of await page.locator('dialog input').all()) await box.check();
  await page.getByText('按所选权限启动',{exact:true}).click();
  const frame=page.frameLocator('#plugin-frame');
  await frame.locator('.pick').first().waitFor();
  await page.waitForFunction(()=>!document.querySelector('#plugin-frame').contentDocument.querySelector('.pick')?.disabled);
  assert.equal(await frame.locator('.card').count(),1, 'slots share one preview');
  assert.equal(await frame.locator('.slot').count(),10, 'ten slots initially');
  assert.equal(await frame.locator('html').getAttribute('lang'),'en');
  assert.equal(await frame.locator('.send').textContent(),wording('发送到眼镜','Send to glasses'));
  assert.equal(await frame.locator('.slot-heading, .card h2').count(),0);
  for (const selector of ['#cancel','#retry','#progress','.connection-actions']) {
    assert.equal(await frame.locator(selector).isVisible(),false);
  }
  assert.equal(await frame.locator('#status').isVisible(),false);
  const layoutPositions=()=>frame.locator('main').evaluate(el=>
    ['header','#slot-list','.image-actions','.feedback','.viewport'].map(selector=>{
      const rect=el.querySelector(selector).getBoundingClientRect();
      return [rect.x,rect.y,rect.width,rect.height];
    }));
  const previewURLs=[];
  await page.evaluate(()=>{const f=document.querySelector('#plugin-frame');f.style.width='390px';f.style.height='1100px';});
  const pngs=await page.evaluate(()=>{
    return [[100,50],[900,500]].map(([w,h])=>{
      const c=document.createElement('canvas');c.width=w;c.height=h;
      const ctx=c.getContext('2d');const image=ctx.createImageData(w,h);
      for(let y=0;y<h;y++)for(let x=0;x<w;x++){
        const p=(y*w+x)*4,gray=((x+y)%16)*17;
        image.data[p]=image.data[p+1]=image.data[p+2]=gray; image.data[p+3]=255;
      }
      ctx.putImageData(image,0,0);return c.toDataURL('image/png').split(',')[1];
    });
  });
  await page.evaluate(()=>{
    const runtime=globalThis.imageTestRuntime;
    const pick=runtime.filePicker;
    runtime.filePicker=options=>{globalThis.imagePickerOptions=options;return pick(options);};
    const dispatch=runtime.dispatch.bind(runtime);
    globalThis.imageReadAttempts=0;
    runtime.dispatch=async(method,params)=>{
      if(method==='files.openRead' && ++globalThis.imageReadAttempts===1) {
        throw Object.assign(new Error('Host file stream failed: HTTP 404'),{code:'INTERNAL_ERROR'});
      }
      return dispatch(method,params);
    };
    const plugin=document.querySelector('#plugin-frame').contentWindow;
    plugin.savedBitmapDecoder=plugin.createImageBitmap;
    plugin.createImageBitmap=async()=>{throw new Error('ImageBitmap unavailable for this format');};
  });
  const files=[];
  for(let i=0;i<2;i++) {const p=join(artifacts,`pattern-${i}.png`);await writeFile(p,Buffer.from(pngs[i],'base64'));files.push(p);}
  for(let i=0;i<2;i++) {
    await frame.locator('.slot').nth(i).click();
    const chooser=page.waitForEvent('filechooser');await frame.locator('.pick').first().click();
    const picker=await chooser;
    assert.equal(await frame.locator('#add-slot').isDisabled(),true);
    assert.equal(await frame.locator('.slot').nth(1-i).isDisabled(),true);
    assert.deepEqual(await page.evaluate(()=>globalThis.imagePickerOptions.extensions),['png','jpg','jpeg','bmp','webp']);
    if(i===0) {
      // Real native pickers temporarily background the plugin. Preserve this
      // particular file operation while still cancelling transfers on suspension.
      await page.evaluate(()=>globalThis.imageTestRuntime.setLifecycle('suspended'));
      await page.evaluate(()=>globalThis.imageTestRuntime.setConnected(false));
      await page.evaluate(()=>globalThis.imageTestRuntime.setLifecycle('running'));
      await page.evaluate(()=>globalThis.imageTestRuntime.setConnected(true));
    }
    await picker.setFiles(files[i]);
    await frame.locator('#status').waitFor({state:'hidden'});
    assert.equal(await frame.locator('#status').isVisible(),false);
    const pixels=await frame.locator('canvas').first().evaluate(canvas=>[...canvas.getContext('2d').getImageData(0,0,600,350).data]);
    for(let y=0;y<350;y++)for(let x=0;x<600;x++){
      const sx=i===0?x-250:x,sy=i===0?y-150:y;
      const expected=i===0&&(sx<0||sx>=100||sy<0||sy>=50)?0:((sx+sy)%16)*17;
      const offset=(y*600+x)*4;
      assert.equal(pixels[offset],0);
      assert.equal(pixels[offset+1],expected,`green pixel mismatch image ${i} at ${x},${y}`);
      assert.equal(pixels[offset+2],0);
      assert.equal(pixels[offset+3],255);
    }
    if(i===0) {
      assert.equal(await page.evaluate(()=>globalThis.imageReadAttempts),2, 'expired ticket must be reopened');
      await page.evaluate(()=>{const plugin=document.querySelector('#plugin-frame').contentWindow;plugin.createImageBitmap=plugin.savedBitmapDecoder;});
    }
    previewURLs.push(await frame.locator('canvas').evaluate(c=>c.toDataURL()));
    await page.evaluate(width=>{document.querySelector('#plugin-frame').style.width=`${width}px`;},i===0?320:390);
    const beforeTransfer=await layoutPositions();
    const chunkReached=new Promise(resolve=>{holdChunk=resolve;});
    await frame.locator('.send').first().click();
    await chunkReached;
    try {
      assert.deepEqual(await layoutPositions(),beforeTransfer,'sending must not move slots, actions or preview');
      assert.equal(await frame.locator('#cancel').isVisible(),true);
      assert.equal(await frame.locator('#progress').isVisible(),true);
      assert.equal(await frame.locator('.name').isVisible(),false);
      await page.locator('#plugin-frame').screenshot({path:join(artifacts,`sending-${i===0?320:390}.png`)});
    } finally { releaseChunk(); }
    await frame.locator('#status').filter({hasText:wording('眼镜已确认显示图片', 'Glasses confirmed display')}).waitFor();
    assert.deepEqual(await layoutPositions(),beforeTransfer,'completion must not move the layout');
  }
  // Switching uses cached pixels and packets, without file reads or automatic sends.
  const cachedReads=await page.evaluate(()=>globalThis.imageReadAttempts);
  for(let n=0;n<10;n++)for(let i=0;i<2;i++) {
    await frame.locator('.slot').nth(i).click();
    assert.equal(await frame.locator('canvas').evaluate(c=>c.toDataURL()),previewURLs[i]);
    assert.equal(await frame.locator('.name').textContent(),`pattern-${i}.png`);
    assert.equal(await frame.locator('.slot').nth(i).getAttribute('aria-pressed'),'true');
  }
  assert.equal(await page.evaluate(()=>globalThis.imageReadAttempts),cachedReads);
  assert.equal(transfers.length,2, 'switching never sends automatically');
  for(let i=0;i<2;i++) {
    await frame.locator('.slot').nth(i).click();
    await frame.locator('.send').click();
    await frame.locator('#status').filter({hasText:wording('眼镜已确认显示图片', 'Glasses confirmed display')}).waitFor();
    assert.deepEqual(transfers[i+2],transfers[i], 'resend uses the original slot frame');
  }
  // Failed persistence must not create a phantom slot or replace a saved image.
  const failNextSave=async()=>page.evaluate(()=>{
    const runtime=globalThis.imageTestRuntime,dispatch=runtime.dispatch.bind(runtime);
    runtime.dispatch=async(method,params)=>{
      if(method==='storage.set') {runtime.dispatch=dispatch;throw Object.assign(new Error('storage write failed'),{code:'INTERNAL_ERROR'});}
      return dispatch(method,params);
    };
  });
  await failNextSave();
  await frame.locator('#add-slot').click();
  await frame.locator('#status.error').filter({hasText:'storage write failed'}).waitFor();
  assert.equal(await frame.locator('.slot').count(),10);
  assert.equal(await frame.locator('canvas').evaluate(c=>c.toDataURL()),previewURLs[1]);
  await frame.locator('#add-slot').click();
  await frame.locator('.slot').nth(10).waitFor();
  assert.equal(await frame.locator('#status').isVisible(),false);
  assert.equal(await frame.locator('.slot').count(),11);
  assert.equal(await frame.locator('.slot').nth(10).getAttribute('aria-pressed'),'true');
  assert.equal(await frame.locator('.send').isDisabled(),true);
  assert.equal(await frame.locator('canvas').evaluate(c=>{
    const rgba=c.getContext('2d').getImageData(0,0,600,350).data;
    return rgba.every((v,i)=>v===(i%4===3?255:0));
  }),true, 'empty slot clears previous pixels to black');
  await frame.locator('.slot').nth(0).click();
  await failNextSave();
  const failedChooser=page.waitForEvent('filechooser');
  await frame.locator('.pick').click();
  await (await failedChooser).setFiles(files[1]);
  await frame.locator('#status.error').filter({hasText:'storage write failed'}).waitFor();
  assert.equal(await frame.locator('canvas').evaluate(c=>c.toDataURL()),previewURLs[0]);
  assert.equal(await frame.locator('.name').textContent(),'pattern-0.png');
  await frame.locator('.slot').nth(1).click();
  // Many slots stay in a bounded panel; adding must keep the trailing + reachable.
  for(let count=12;count<=40;count++) {
    await frame.locator('#add-slot').click();
    await frame.locator('.slot').nth(count-1).waitFor();
    assert.equal(await frame.locator('.slot').count(),count);
  }
  await frame.locator('#slot-list').evaluate(el=>{el.scrollTop=0;});
  await frame.locator('.slot').nth(1).click();
  assert.equal(await frame.locator('canvas').evaluate(c=>c.toDataURL()),previewURLs[1]);
  for(const width of [320,390]) {
    await page.evaluate(w=>{const f=document.querySelector('#plugin-frame');f.style.width=`${w}px`;f.style.height='640px';},width);
    const layout=await frame.locator('main').evaluate(el=>{
      const list=el.querySelector('#slot-list'),add=el.querySelector('#add-slot');
      const send=el.querySelector('.send').getBoundingClientRect(),preview=el.querySelector('.viewport').getBoundingClientRect();
      return {panelHeight:list.getBoundingClientRect().height,scrollable:list.scrollHeight>list.clientHeight,
        plusLast:list.lastElementChild===add,sendAbove:send.bottom<=preview.top,
        sendVisible:send.top>=0&&send.bottom<=innerHeight,previewVisible:preview.bottom<=innerHeight,
        horizontalOverflow:document.documentElement.scrollWidth>innerWidth};
    });
    assert.ok(layout.panelHeight<=132);
    assert.equal(layout.scrollable,true);
    assert.equal(layout.plusLast,true);
    assert.equal(layout.sendAbove,true);
    assert.equal(layout.sendVisible,true);
    assert.equal(layout.previewVisible,true);
    assert.equal(layout.horizontalOverflow,false);
  }
  const mobile=await frame.locator('main').evaluate(el=>{
    const c=el.querySelector('canvas'), rect=c.getBoundingClientRect(), viewport=el.querySelector('.viewport');
    return {w:c.width,h:c.height,physicalW:rect.width*devicePixelRatio,physicalH:rect.height*devicePixelRatio,
      fits:rect.left>=0&&rect.right<=innerWidth,scrolls:viewport.scrollWidth>viewport.clientWidth};
  });
  assert.equal(mobile.w,600);assert.equal(mobile.h,350);
  assert.ok(Math.abs(mobile.physicalW-600)<0.05);
  assert.ok(Math.abs(mobile.physicalH-350)<0.05);
  assert.equal(mobile.fits,true);assert.equal(mobile.scrolls,false);
  await page.locator('#plugin-frame').screenshot({path:join(artifacts,'phone-preview.png')});
  // Repeated checks must preserve the document, canvas and Host subscription.
  await frame.locator('canvas').evaluate(canvas=>{
    window.reconnectCanvas=canvas;window.reconnectPixels=canvas.toDataURL();
  });
  const beforeCheck=await page.evaluate(()=>({reads:globalThis.imageReadAttempts,
    calls:globalThis.imageMethodCounts['device.getInfo'],subs:globalThis.imageTestRuntime.subscriptions.size}));
  await frame.locator('#retry').evaluate(button=>{for(let i=0;i<20;i++)button.click();});
  await frame.locator('#status').waitFor({state:'hidden'});
  assert.equal(await frame.locator('#retry').isEnabled(),true);
  assert.equal(await frame.locator('canvas').evaluate(c=>c===window.reconnectCanvas&&c.toDataURL()===window.reconnectPixels),true);
  assert.equal(await page.evaluate(()=>globalThis.imageReadAttempts),beforeCheck.reads);
  assert.equal(await page.evaluate(()=>globalThis.imageMethodCounts['device.getInfo']),beforeCheck.calls+1);
  assert.equal(await page.evaluate(()=>globalThis.imageTestRuntime.subscriptions.size),beforeCheck.subs);
  // Simulated persisted page restore retains its bridge; it must not navigate.
  await frame.locator('main').evaluate(()=>{
    window.dispatchEvent(new PageTransitionEvent('pagehide',{persisted:true}));
    window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true}));
  });
  await frame.locator('#status').waitFor({state:'hidden'});
  assert.equal(await frame.locator('canvas').evaluate(c=>c===window.reconnectCanvas),true);
  // A failed connection check returns control and leaves local image tools usable.
  await page.evaluate(()=>{
    const runtime=globalThis.imageTestRuntime,dispatch=runtime.dispatch.bind(runtime);
    runtime.dispatch=async(method,params)=>{
      if(method==='device.getInfo') {runtime.dispatch=dispatch;throw Object.assign(new Error('connection check failed'),{code:'INTERNAL_ERROR'});}
      return dispatch(method,params);
    };
  });
  await frame.locator('main').evaluate(()=>{
    window.dispatchEvent(new PageTransitionEvent('pagehide',{persisted:true}));
    window.dispatchEvent(new PageTransitionEvent('pageshow',{persisted:true}));
  });
  await frame.locator('#status.error').filter({hasText:'connection check failed'}).waitFor();
  assert.equal(await frame.locator('#retry').isVisible(),true);
  assert.equal(await frame.locator('.pick').isEnabled(),true);
  await frame.locator('#retry').click();
  await frame.locator('#status').waitFor({state:'hidden'});
  await page.locator('#connected').uncheck();
  await page.waitForFunction(()=>document.querySelector('#plugin-frame').contentDocument.querySelector('.send').disabled);
  assert.equal(await frame.locator('.send').first().isDisabled(),true);
  assert.equal(await frame.locator('#retry').isVisible(),true);
  await page.locator('#connected').check();
  await page.locator('#lifecycle').selectOption('suspended');
  await page.waitForFunction(()=>document.querySelector('#plugin-frame').contentDocument.querySelector('.pick').disabled);
  assert.equal(await frame.locator('.pick').first().isDisabled(),true);
  await page.locator('#lifecycle').selectOption('running');
  await page.waitForFunction(()=>!document.querySelector('#plugin-frame').contentDocument.querySelector('.pick').disabled);
  // Picker cancellation preserves the current replacement image.
  await page.evaluate(()=>{globalThis.imageTestRuntime.filePicker=async()=>[];});
  await frame.locator('.pick').first().click();
  await frame.locator('#status').filter({hasText:wording('已取消选择', 'Selection cancelled')}).waitFor();
  assert.equal(await frame.locator('.name').first().textContent(),'pattern-1.png');
  // Reopen through the Host to verify persistence and initialization retry.
  // This is an explicit Host restart, never the plugin's connection button.
  await page.evaluate(()=>{globalThis.imageFailReady=true;globalThis.imageFailSubscribe=true;});
  await page.locator('#reload').click();
  await page.locator('dialog').waitFor();
  for(const box of await page.locator('dialog input').all()) await box.check();
  await page.getByText('按所选权限启动',{exact:true}).click();
  await frame.locator('#status.error').filter({hasText:'temporary ready failure'}).waitFor();
  await frame.locator('main').evaluate(()=>{window.retryDocument=document;});
  await frame.locator('#retry').click();
  await frame.locator('#status').filter({hasText:wording('可以选择和预览本地图片', 'Local images are available')}).waitFor();
  assert.equal(await frame.locator('main').evaluate(()=>document===window.retryDocument),true);
  assert.equal(await frame.locator('.pick').isEnabled(),true);
  assert.equal(await frame.locator('.slot').count(),40, 'all added slots survive Host restart');
  await frame.locator('.name').filter({hasText:'pattern-0.png'}).waitFor({state:'attached'});
  assert.equal(await frame.locator('canvas').evaluate(c=>c.toDataURL()),previewURLs[0]);
  await frame.locator('.slot').nth(1).click();
  await frame.locator('.name').filter({hasText:'pattern-1.png'}).waitFor();
  assert.equal(await frame.locator('canvas').evaluate(c=>c.toDataURL()),previewURLs[1]);
  await page.evaluate(()=>{globalThis.imageFailSubscribe=false;});
  await frame.locator('#retry').click();
  await frame.locator('#status').waitFor({state:'hidden'});
  assert.equal(await page.evaluate(()=>globalThis.imageTestRuntime.subscriptions.size),1);
  const beforeLocale=await page.evaluate(()=>({reads:globalThis.imageReadAttempts,subs:globalThis.imageTestRuntime.subscriptions.size}));
  const switchLocale=async language=>{
    await frame.locator('main').evaluate((_,language)=>{
      Object.defineProperty(navigator,'language',{configurable:true,value:language});
      Object.defineProperty(navigator,'languages',{configurable:true,value:[language]});
      window.dispatchEvent(new Event('languagechange'));
    },language);
  };
  const otherLocale=locale.startsWith('zh')?'en-US':'zh-CN';
  await switchLocale(otherLocale);
  assert.equal(await frame.locator('.send').textContent(),'Send to glasses');
  assert.equal(await frame.locator('#status').isVisible(),false);
  assert.equal(await frame.locator('main').evaluate(()=>document===window.retryDocument),true);
  assert.equal(await frame.locator('canvas').evaluate(c=>c.toDataURL()),previewURLs[1]);
  assert.equal(await page.evaluate(()=>globalThis.imageReadAttempts),beforeLocale.reads);
  assert.equal(await page.evaluate(()=>globalThis.imageTestRuntime.subscriptions.size),beforeLocale.subs);
  await switchLocale(locale);
  await page.evaluate(()=>{
    delete globalThis.imageTestRuntime.approvals['device.messaging'];
  });
  const beforeError=await layoutPositions();
  await frame.locator('.send').first().click();
  await frame.locator('#status.error').waitFor();
  assert.deepEqual(await layoutPositions(),beforeError,'errors must not move the preview');
  assert.ok((await frame.locator('#status').textContent()).includes(wording('权限被拒绝','Permission denied')));
  await switchLocale(otherLocale);
  assert.ok((await frame.locator('#status').textContent()).includes('Permission denied'));
  await switchLocale(locale);
  assert.deepEqual(errors,[]);
  assert.equal(transfers.length,4);
  for(const transfer of transfers) {
    assert.equal(transfer.lz4,true);assert.equal(transfer.bytes,transfer.expected);
    assert.ok(transfer.bytes<105000);assert.ok(transfer.chunks<26);
  }
  console.log('LZ4 image transfers:',JSON.stringify(transfers));
  const exit=new Promise(resolve=>device.once('exit',resolve));device.stdin.end();assert.equal(await exit,0);
  const rendered=await readFile(join(artifacts,'frame.gray4'));
  assert.equal(rendered.length,105000);
  for(let y=0;y<350;y++)for(let x=0;x<600;x+=2)assert.equal(rendered[y*300+x/2],(((x+y)%16)<<4)|((x+y+1)%16));
  console.log(`PASS (${locale}, fixed English UI/status/errors across locale changes; cache preserved): 420000 green pixels checked, 10 initial slots, inline plus, 40 slots in bounded panel, send above preview visible at 320/390x640, cached switches, empty preview, failed saves, independent slot persistence, DPR=3 preview occupies 600x350 physical pixels without overflow; capability inventory without methods, picker suspend/return, expired-ticket retry, decoder fallback, two real PNG picks and a failed replacement; Web SDK → LZ4 → public Host → native reference LZ4 → C → FrameBuffer; connection checks without navigation, rapid clicks, persisted-page restore, initialization retry, Host restart, cancellation, disconnection, lifecycle and revocation. Artifacts: ${artifacts}`);
} finally {await browser?.close();server.kill();device.kill();}
