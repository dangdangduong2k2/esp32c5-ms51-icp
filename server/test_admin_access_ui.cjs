const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
(async()=>{
 const root=path.resolve(__dirname,'..');
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const page=await browser.newPage();
 const catalog=JSON.parse(fs.readFileSync(path.join(__dirname,'settings_catalog.json'),'utf8'));
 for(const key of ['showDf','showEnd','showSl','showHcf','showHdf','showSt','defrostOpSeconds'])delete catalog[key];
 let mode='ok',posts=0,reads=0,releaseOld;
 await page.route('http://admin.test/**',async route=>{
  const req=route.request(),url=new URL(req.url());
  if(req.method()!=='GET')posts++;
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
  assert.equal(await page.locator('#permission-fields input:checked').count(),2);
  assert.equal(await page.locator('#permission-fields input[value="coolCl"]').isChecked(),true);
  assert.equal(await page.locator('#permission-fields input[value="coolOp"]').isChecked(),false);
  assert.equal(await page.locator('#option-show-st').isChecked(),true);
  assert.equal(await page.locator('#option-op-seconds').isChecked(),false);
  assert.equal(await page.locator('#save-options').isEnabled(),true);assert.equal(reads,1);assert.equal(posts,0);
  await close();mode='options-fail';await open('device-a');
  assert.equal(await page.locator('#save-policy').isEnabled(),true);
  assert.equal(await page.locator('#save-options').isDisabled(),true);
  assert.equal(await page.locator('#option-show-st').evaluate(n=>n.indeterminate),true);
  assert.match(await page.locator('#options-status').textContent(),/Chưa đọc được/);
  await close();mode='access-fail';await open('device-a');
  assert.equal(await page.locator('#save-policy').isDisabled(),true);
  await page.locator('#read-options').click();await page.waitForFunction(()=>!optionsLoading);
  assert.equal(await page.locator('#save-policy').isDisabled(),true);
  await close();mode='late';
  const oldOpen=open('device-a');
  await page.waitForFunction(()=>optionsLoading&&policyLoaded);
  assert.equal(await page.locator('#close-access').isEnabled(),true);
  await page.locator('#close-access').click();await open('device-b');
  releaseOld();await oldOpen;
  assert.equal(await page.locator('#option-show-st').isChecked(),false);
  assert.equal(await page.locator('#option-op-seconds').isChecked(),true);
  assert.equal(await page.evaluate(()=>accessTarget.device),'device-b');
  assert.equal(posts,0);
  console.log('PASS: saved permissions selected, automatic MCU GET, offline unknown state, failed policy stays unsaveable, manual retry, close while loading, late result isolation, zero writes.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
