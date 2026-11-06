const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const page=await browser.newPage({viewport:{width:1000,height:900}});
 const catalog=JSON.parse(fs.readFileSync(path.join(__dirname,'settings_catalog.json'),'utf8'));
 for(const key of ['showDf','showEnd','showSl','showHcf','showHdf','showSt','defrostOpSeconds','tryTime','tryUnit'])delete catalog[key];
 let mode='expired',grant=null,writes=[],applications=new Set();
 const usage=()=>({ok:true,state:mode==='expired'?'expired':'active',period:24,unit:'hours',remaining_seconds:mode==='expired'?0:86400,
   revision:'a'.repeat(64),supported:mode!=='unsupported',grant});
 await page.route('http://admin.test/**',async route=>{
  const req=route.request(),url=new URL(req.url());
  if(url.pathname.startsWith('/api/')){
   if(url.pathname==='/api/session')return route.fulfill({status:401,json:{message:'Sign in'}});
   if(url.pathname.endsWith('/access'))return route.fulfill({json:{ok:true,revision:1,visible:[],catalog,firmware:null,job:null}});
   if(url.pathname.endsWith('/options'))return route.fulfill({json:{ok:true,values:{showSt:0,defrostOpSeconds:0},revision:'b'.repeat(64)}});
   if(url.pathname.endsWith('/usage')){
    if(req.method()==='GET')return route.fulfill(mode==='offline'?{status:409,json:{message:'Thiết bị đang ngoại tuyến.'}}:{json:usage()});
    const body=req.postDataJSON();writes.push(body);
    if(mode==='lost'){
     grant={...body,state:'unknown',message:'Chưa xác nhận gia hạn.'};
     return route.fulfill({status:504,json:{message:'Chưa nhận được phản hồi.'}});
    }
    applications.add(body.id);grant={...body,state:'applied',message:'Mạch đã xác nhận gia hạn.'};mode='active';
    return route.fulfill({json:{ok:true,grant}});
   }
   return route.fulfill({status:404,json:{message:'fixture'}});
  }
  const rel=url.pathname==='/'?'index.html':url.pathname.replace(/^\/assets\//,'');
  return route.fulfill({path:path.join(__dirname,'admin_web',rel)});
 });
 const open=()=>page.evaluate(async()=>{state.csrf='fixture';await openAccess('u1',{id:'device-a',name:'Bộ điều khiển'});});
 const close=()=>page.evaluate(()=>closeAccess());
 try{
  await page.goto('http://admin.test/');await page.waitForFunction(()=>!document.getElementById('login').hidden);
  await open();assert.match(await page.locator('#usage-state').textContent(),/Đã hết/);
  assert.equal(await page.locator('#renew-usage').isEnabled(),true);
  await page.locator('#usage-amount').fill('12');await page.locator('#usage-unit').selectOption('hours');
  await page.locator('#renew-usage').click();await page.waitForFunction(()=>document.getElementById('usage-state').textContent==='Đang sử dụng'&&!state.pending);
  assert.equal(writes.length,1);assert.equal(writes[0].amount,12);assert.match(writes[0].id,/^[a-f0-9]{32}$/);assert.equal(applications.size,1);
  await close();mode='unsupported';grant=null;await open();assert.equal(await page.locator('#renew-usage').isDisabled(),true);
  assert.match(await page.locator('#usage-status').textContent(),/cập nhật/);
  await close();mode='offline';await open();assert.equal(await page.locator('#renew-usage').isDisabled(),true);
  assert.equal(await page.locator('#read-usage').isEnabled(),true);assert.match(await page.locator('#usage-error').textContent(),/ngoại tuyến/);
  await close();mode='lost';grant=null;await open();await page.locator('#renew-usage').click();
  await page.waitForFunction(()=>!state.pending&&document.getElementById('usage-error').textContent.length>0);
  const first=writes.at(-1);assert.equal(await page.locator('#usage-amount').isDisabled(),true);
  await page.locator('#read-usage').click();await page.waitForFunction(()=>!usageLoading);
  assert.equal(await page.locator('#renew-usage').textContent(),'Thử lại gia hạn');mode='active';
  await page.locator('#renew-usage').click();await page.waitForFunction(()=>!state.pending&&!usageLoading);
  assert.equal(writes.at(-1).id,first.id);assert.equal(applications.size,2);
  await page.setViewportSize({width:390,height:900});await page.locator('#usage-title').scrollIntoViewIfNeeded();
  assert.equal(await page.evaluate(()=>document.getElementById('access-dialog').scrollWidth<=document.getElementById('access-dialog').clientWidth),true);
  assert.equal(await page.locator('label[for="usage-amount"]').count(),1);
  if(process.env.MS51_UI_PREVIEW){fs.mkdirSync(process.env.MS51_UI_PREVIEW,{recursive:true});await page.locator('.usage-section').screenshot({path:path.join(process.env.MS51_UI_PREVIEW,'admin-gia-han.png')});}
  console.log('PASS: expired/active view, targeted grant, offline/unsupported guards, same-token retry, mobile layout');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
