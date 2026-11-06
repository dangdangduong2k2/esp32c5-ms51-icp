const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const page=await browser.newPage({viewport:{width:1000,height:900}});
 const catalog=JSON.parse(fs.readFileSync(path.join(__dirname,'settings_catalog.json'),'utf8'));
 for(const key of ['showDf','showEnd','showSl','showHcf','showHdf','showSt','defrostOpSeconds','tryTime','tryUnit'])delete catalog[key];
 let mode='expired',grant=null,writes=[],applications=new Set(),boardPeriod=4,boardUnit='hours';
 const usage=()=>({ok:true,state:mode==='expired'?'expired':mode==='unlimited'?'unlimited':'active',period:boardPeriod,unit:boardUnit,remaining_seconds:mode==='unlimited'?null:mode==='expired'?0:7200,
   revision:'a'.repeat(64),supported:mode!=='unsupported',unlimited_supported:!['unsupported','legacy'].includes(mode),grant});
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
    applications.add(body.id);boardPeriod=body.amount;boardUnit=body.unit;
    grant={...body,state:'applied',message:body.amount===0?'Đã mở không giới hạn thời gian.':'Mạch đã xác nhận gia hạn.'};mode=body.amount===0?'unlimited':'active';
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
  assert.equal(await page.locator('#usage-amount').inputValue(),'4');
  assert.equal(await page.locator('#usage-unit').inputValue(),'hours');
  // Read the board's existing TRY even with no server grant history; never write/reset it.
  mode='active';boardPeriod=8;boardUnit='days';
  await page.locator('#usage-amount').fill('99');await page.locator('#usage-unit').selectOption('hours');
  await page.locator('#read-usage').click();await page.waitForFunction(()=>!usageLoading);
  assert.equal(await page.locator('#usage-amount').inputValue(),'8');assert.equal(await page.locator('#usage-unit').inputValue(),'days');
  assert.equal(writes.length,0);assert.match(await page.locator('#usage-remaining').textContent(),/2 giờ/);
  // The upper shared read button must restore TRY too, not just the menu options.
  boardPeriod=4;boardUnit='hours';
  await page.locator('#usage-amount').fill('88');await page.locator('#usage-unit').selectOption('days');
  await page.locator('#read-options').click();await page.waitForFunction(()=>!optionsLoading&&!usageLoading);
  assert.equal(await page.locator('#usage-amount').inputValue(),'4');assert.equal(await page.locator('#usage-unit').inputValue(),'hours');
  assert.match(await page.locator('#usage-status').textContent(),/Đã đọc từ mạch/);assert.equal(writes.length,0);
  await page.locator('#usage-amount').fill('12');await page.locator('#usage-unit').selectOption('hours');
  await page.locator('#renew-usage').click();await page.waitForFunction(()=>document.getElementById('usage-state').textContent==='Đang sử dụng'&&!state.pending);
  assert.equal(writes.length,1);assert.equal(writes[0].amount,12);assert.match(writes[0].id,/^[a-f0-9]{32}$/);assert.equal(applications.size,1);
  assert.equal(await page.locator('#usage-amount').inputValue(),'12');assert.equal(await page.locator('#usage-unit').inputValue(),'hours');
  await close();mode='unsupported';grant=null;await open();assert.equal(await page.locator('#renew-usage').isDisabled(),true);
  assert.match(await page.locator('#usage-status').textContent(),/cập nhật/);
  await close();mode='offline';await open();assert.equal(await page.locator('#renew-usage').isDisabled(),true);
  assert.equal(await page.locator('#read-usage').isEnabled(),true);assert.match(await page.locator('#usage-error').textContent(),/ngoại tuyến/);
  await close();mode='lost';grant=null;await open();await page.locator('#renew-usage').click();
  await page.waitForFunction(()=>!state.pending&&document.getElementById('usage-error').textContent.length>0);
  const first=writes.at(-1);assert.equal(await page.locator('#usage-amount').isDisabled(),true);
  boardPeriod=44;boardUnit='days'; // unresolved retry must retain the original transaction values
  await page.locator('#read-usage').click();await page.waitForFunction(()=>!usageLoading);
  assert.equal(await page.locator('#usage-amount').inputValue(),String(first.amount));assert.equal(await page.locator('#usage-unit').inputValue(),first.unit);
  assert.equal(await page.locator('#renew-usage').textContent(),'Thử lại gia hạn');mode='active';
  await page.locator('#renew-usage').click();await page.waitForFunction(()=>!state.pending&&!usageLoading);
  assert.equal(writes.at(-1).id,first.id);assert.equal(applications.size,2);
  await close();mode='legacy';grant=null;await open();
  await page.locator('#usage-amount').fill('0');assert.equal(await page.locator('#usage-unit').isDisabled(),true);
  await page.locator('#renew-usage').click();assert.match(await page.locator('#usage-error').textContent(),/3\.7\.6/);
  assert.equal(writes.length,3); // legacy MS51 cannot receive an unsupported zero envelope
  await close();mode='expired';await open();await page.locator('#usage-amount').fill('0');
  await page.locator('#renew-usage').click();await page.waitForFunction(()=>document.getElementById('usage-state').textContent==='Không giới hạn'&&!state.pending);
  assert.equal(writes.at(-1).amount,0);assert.equal(await page.locator('#usage-remaining').textContent(),'');
  assert.equal(await page.locator('#usage-amount').inputValue(),'0');assert.equal(await page.locator('#usage-unit').isDisabled(),true);
  assert.match(await page.locator('#usage-status').textContent(),/không giới hạn/);
  await page.locator('#usage-amount').fill('1');assert.equal(await page.locator('#usage-unit').isEnabled(),true);
  await page.locator('#usage-amount').fill('0');
  await page.setViewportSize({width:390,height:900});await page.locator('#usage-title').scrollIntoViewIfNeeded();
  assert.equal(await page.evaluate(()=>document.getElementById('access-dialog').scrollWidth<=document.getElementById('access-dialog').clientWidth),true);
  assert.equal(await page.locator('label[for="usage-amount"]').count(),1);
  if(process.env.MS51_UI_PREVIEW){fs.mkdirSync(process.env.MS51_UI_PREVIEW,{recursive:true});await page.locator('.usage-section').screenshot({path:path.join(process.env.MS51_UI_PREVIEW,'admin-gia-han.png')});}
  console.log('PASS: both read buttons restore TRY and unit without writes, finite/unlimited grants, pending receipt isolation, legacy guards, mobile layout');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
