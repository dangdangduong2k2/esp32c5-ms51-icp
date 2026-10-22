const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
(async()=>{
 const root=path.resolve(__dirname,'..');
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const page=await browser.newPage();
 const catalog=JSON.parse(fs.readFileSync(path.join(__dirname,'settings_catalog.json'),'utf8'));
 for(const key of ['showDf','showEnd','showSl','showHcf','showHdf','showSt','defrostOpSeconds'])delete catalog[key];
 let mode='ok',posts=0,reads=0,releaseOld,writes=[];
 await page.route('http://admin.test/**',async route=>{
  const req=route.request(),url=new URL(req.url());
  if(req.method()!=='GET'){
   posts++;writes.push({path:url.pathname,body:req.postDataJSON()});
   if(mode==='save-policy-fail'&&url.pathname.endsWith('/access'))return route.fulfill({status:409,json:{message:'Quyền đã thay đổi'}});
   if(mode==='save-options-fail'&&url.pathname.endsWith('/options'))return route.fulfill({status:504,json:{message:'Mạch chưa phản hồi'}});
   return route.fulfill({json:{ok:true,revision:7,changed:true}});
  }
  if(url.pathname.includes('/api/')){
   if(url.pathname.endsWith('/session'))return route.fulfill({status:401,json:{message:'Sign in'}});
   if(url.pathname.endsWith('/access'))return route.fulfill(mode==='access-fail'?{status:500,json:{message:'Fixture policy unavailable'}}:{json:{ok:true,revision:6,visible:['coolCl','pinEnable'],catalog,firmware:null,job:null}});
   if(url.pathname.endsWith('/options')){
    reads++;
    if(mode==='late'&&url.pathname.includes('/device-a/'))await new Promise(r=>releaseOld=r);
    if(mode==='options-fail')return route.fulfill({status:504,json:{message:'Thiết bị chưa phản hồi'}});
    const second=url.pathname.includes('/device-b/');
    return route.fulfill({json:{ok:true,values:{showSt:second?0:1,defrostOpSeconds:second?1:0},revision:(second?'b':'a').repeat(64)}});
   }
   return route.fulfill({status:404,json:{message:'fixture'}});
  }
  const rel=url.pathname==='/'?'index.html':url.pathname.replace(/^\/assets\//,'');
  return route.fulfill({path:path.join(__dirname,'admin_web',rel)});
 });
 const open=async id=>page.evaluate(async id=>{state.csrf='fixture';await openAccess('user-a',{id,name:'Thiết bị kiểm tra'});},id);
 const close=()=>page.evaluate(()=>closeAccess());
 try{
  await page.goto('http://admin.test/');await page.waitForFunction(()=>document.getElementById('login').hidden===false);
  await open('device-a');
  assert.equal(await page.locator('#permission-fields input[data-permission]:checked').count(),2);
  assert.deepEqual(await page.locator('#permission-fields > fieldset > legend').allTextContents(),['Thời gian hoạt động','Cài đặt nâng cao','Giới hạn thời gian (Min / Max)','Mật khẩu bảo vệ']);
  assert.deepEqual((await page.locator('input[data-permission]').evaluateAll(ns=>ns.map(n=>n.value))).sort(),Object.keys(catalog).sort());
  assert.equal(await page.locator('#option-show-st').evaluate(n=>n.closest('.permission-subgroup').querySelector('h4').textContent),'Cài đặt khởi động');
  assert.equal(await page.locator('#option-op-seconds').evaluate(n=>n.closest('.permission-subgroup').querySelector('h4').textContent),'Chu trình');
  assert.equal(await page.locator('#option-show-st').evaluate(n=>n.form.id),'policy-form');
  assert.equal(await page.locator('#permission-fields input[value="coolCl"]').isChecked(),true);
  assert.equal(await page.locator('#permission-fields input[value="coolOp"]').isChecked(),false);
  assert.equal(await page.locator('#option-show-st').isChecked(),true);
  assert.equal(await page.locator('#option-op-seconds').isChecked(),false);
  assert.equal(await page.locator('#save-access').isEnabled(),true);assert.equal(reads,1);assert.equal(posts,0);
  if(process.env.MS51_UI_PREVIEW){
   fs.mkdirSync(process.env.MS51_UI_PREVIEW,{recursive:true});
   await page.screenshot({path:path.join(process.env.MS51_UI_PREVIEW,'admin-desktop.png')});
   await page.setViewportSize({width:390,height:844});
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
   await page.screenshot({path:path.join(process.env.MS51_UI_PREVIEW,'admin-mobile.png')});
   await page.setViewportSize({width:1280,height:720});
  }
  await page.locator('#show-all').click();
  assert.equal(await page.locator('input[data-permission]:checked').count(),44);
  assert.equal(await page.locator('#option-op-seconds').isChecked(),false);
  await page.locator('#hide-all').click();
  assert.equal(await page.locator('input[data-permission]:checked').count(),0);
  assert.equal(await page.locator('#option-show-st').isChecked(),true);
  await page.locator('input[value="coolCl"]').check();
  assert.equal(await page.locator('#save-options, #save-policy').count(),0);
  await page.locator('#option-op-seconds').check();
  await page.locator('#save-access').click();await page.waitForFunction(()=>!state.pending);
  assert.equal(writes.length,2);assert.match(writes[0].path,/\/access$/);assert.match(writes[1].path,/\/options$/);
  assert.deepEqual(writes[0].body.visible,['coolCl']);
  assert.deepEqual(writes[1].body.values,{showSt:1,defrostOpSeconds:1});
  assert.match(await page.locator('#options-status').textContent(),/đã xác nhận/);
  await page.locator('#save-access').click();await page.waitForFunction(()=>!state.pending);
  assert.equal(writes.length,3);assert.match(writes[2].path,/\/access$/); // no duplicate MCU write
  await close();await open('device-a');writes=[];
  await page.locator('#option-op-seconds').check();mode='save-policy-fail';
  await page.locator('#save-access').click();await page.waitForFunction(()=>!state.pending);
  assert.equal(writes.length,1);assert.match(writes[0].path,/\/access$/);
  assert.match(await page.locator('#access-error').textContent(),/chưa gửi thay đổi xuống mạch/);
  mode='save-options-fail';writes=[];
  await page.locator('#save-access').click();await page.waitForFunction(()=>!state.pending);
  assert.equal(writes.length,2);
  assert.match(await page.locator('#access-error').textContent(),/Đã lưu quyền hiển thị; chưa xác nhận/);
  await page.locator('#save-access').click();await page.waitForFunction(()=>!state.pending);
  assert.equal(writes.length,2); // uncertain MCU write cannot be retried without a read
  posts=0;
  await close();mode='options-fail';await open('device-a');
  assert.equal(await page.locator('#save-access').isEnabled(),true);
  assert.equal(await page.locator('#option-show-st').isDisabled(),true);
  assert.equal(await page.locator('#option-show-st').evaluate(n=>n.indeterminate),true);
  assert.match(await page.locator('#options-status').textContent(),/Chưa đọc được/);
  writes=[];await page.locator('#save-access').click();await page.waitForFunction(()=>!state.pending);
  assert.equal(writes.length,1);assert.match(writes[0].path,/\/access$/);
  assert.match(await page.locator('#toast').textContent(),/phần này được giữ nguyên/);posts=0;
  await close();mode='access-fail';await open('device-a');
  assert.equal(await page.locator('#save-access').isDisabled(),true);
  await page.locator('#read-options').click();await page.waitForFunction(()=>!optionsLoading);
  assert.equal(await page.locator('#save-access').isDisabled(),true);
  await close();mode='late';
  const oldOpen=open('device-a');
  await page.waitForFunction(()=>optionsLoading&&policyLoaded);
  assert.equal(await page.locator('#close-access').isEnabled(),true);
  await page.locator('#close-access').click();await open('device-b');
  releaseOld();await oldOpen;
  assert.equal(await page.locator('#option-show-st').isChecked(),false);
  assert.equal(await page.locator('#option-op-seconds').isChecked(),true);
  assert.equal(await page.evaluate(()=>accessTarget.device),'device-b');
  assert.equal(await page.locator('#option-show-st').count(),1);
  assert.equal(await page.locator('#option-op-seconds').count(),1);
  assert.equal(posts,0);
  console.log('PASS: app grouping, all 44 permissions exactly once, integrated MCU options, single save, partial failure handling, no duplicate device writes, bulk action isolation, automatic GET, offline/error handling, late result isolation, no implicit writes.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
