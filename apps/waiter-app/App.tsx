import React, { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import * as SecureStore from "expo-secure-store";
import { ApiError, ElepayClient, MerchantView, formatMoney, newKey } from "@elepay/mobile-core";
import {
  TERMINAL_WAITER_SUBMIT_ERROR_CODES,
  WAITER_COURSES,
  WaiterCourse,
  WaiterDraft,
  WaiterLine,
  WaiterOrder,
  WaiterTable,
  buildWaiterSubmission,
  canAddToTable,
  canSelectTable,
  formatTableButtonLabel,
  isOrderModifiableStatus,
  normalizeStoredDraft,
} from "./src/waiterOrderHelpers";

type Table = WaiterTable;
type Menu = { variantId: string; name: string; category: string | null; pricePesewas: number };
type Line = WaiterLine;
type Order = WaiterOrder;
type Draft = WaiterDraft;
const SESSION = "elepay_waiter_session";
const PENDING = "elepay_waiter_pending";
const message = (error: unknown) => error instanceof Error ? error.message : String(error);

function Button({label, onPress, disabled = false}: {label: string; onPress: () => void; disabled?: boolean}) {
  return <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[s.button, disabled && s.disabled]}><Text style={s.buttonText}>{label}</Text></Pressable>;
}

function Waiter({client, merchant, logout}: {client: ElepayClient; merchant: MerchantView; logout: () => void}) {
  const prefix = "/v1/restaurant/waiter/" + merchant.id;
  const [tables,setTables] = useState<Table[]>([]);
  const [menu,setMenu] = useState<Menu[]>([]);
  const [orders,setOrders] = useState<Order[]>([]);
  const [table,setTable] = useState("");
  const [lines,setLines] = useState<Line[]>([]);
  const [notes,setNotes] = useState("");
  const [search,setSearch] = useState("");
  const [pending,setPending] = useState<Draft|null>(null);
  const [booted,setBooted] = useState(false);
  const [storageBlocked,setStorageBlocked] = useState(false);
  const [busy,setBusy] = useState(false);
  const [error,setError] = useState("");
  const [notice,setNotice] = useState("");
  const [tab,setTab] = useState<"order"|"track">("order");
  const sending = useRef(false);
  const ready = useRef(new Set<string>());

  useEffect(() => {
    let alive = true;
    SecureStore.getItemAsync(PENDING).then(raw => {
      if (!alive || !raw) return;
      const draft = normalizeStoredDraft(JSON.parse(raw) as Draft);
      if (draft.merchant !== merchant.id) throw new Error("Another restaurant has an unresolved order on this device.");
      setPending(draft); setTable(draft.tableId); setLines(draft.items); setNotes(draft.notes);
    }).catch(e => { if (alive) { setStorageBlocked(true); setError(message(e)); } }).finally(() => { if (alive) setBooted(true); });
    return () => { alive = false; };
  }, [merchant.id]);

  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const [t,m,o] = await Promise.all([client.call<Table[]>(prefix+"/tables"),client.call<Menu[]>(prefix+"/menu"),client.call<Order[]>(prefix+"/orders")]);
        if (!alive) return;
        setTables(t); setMenu(m); setOrders(o);
        const now = new Set(o.filter(x=>x.kitchenStatus==="READY").map(x=>x.orderId));
        const newlyReady = o.filter(x=>now.has(x.orderId)&&!ready.current.has(x.orderId));
        ready.current = now;
        if (newlyReady.length) setNotice("Food ready: " + newlyReady.map(x=>"Table "+x.tableNumber).join(", "));
      } catch(e) { if (alive) setError(message(e)); }
      finally { if (alive) timer=setTimeout(()=>void refresh(),5000); }
    }
    void refresh();
    return ()=>{alive=false;clearTimeout(timer);};
  },[client,prefix]);

  const selectedTable = useMemo(() => tables.find(t => t.id === table) ?? null, [tables, table]);
  const activeAddOnOrderId = pending
    ? (pending.orderId ?? null)
    : (selectedTable && canAddToTable(selectedTable, orders) ? selectedTable.currentOrderId : null);

  function change(id:string,delta:number) {
    if (pending || sending.current) return;
    setLines(old=>{
      const found=old.find(x=>x.variantId===id);
      if (!found) return delta>0&&old.length<100?[...old,{variantId:id,quantity:1,notes:"",course:"MAIN"}]:old;
      return old.map(x=>x.variantId===id?{...x,quantity:Math.min(99,x.quantity+delta)}:x).filter(x=>x.quantity>0);
    });
  }
  function setLineCourse(id:string,course:WaiterCourse) {
    if (pending || sending.current) return;
    setLines(old=>old.map(x=>x.variantId===id?{...x,course}:x));
  }
  function startAddItems(order:Order) {
    if (busy || pending) return;
    const match = tables.find(t => t.currentOrderId === order.orderId);
    if (!match) {
      setError("Table for order " + order.orderNumber + " is not currently available.");
      return;
    }
    setError("");
    setTable(match.id);
    setTab("order");
  }
  async function submit() {
    if (sending.current || !booted || storageBlocked || (!pending && (!table || !lines.length || !selectedTable || !canSelectTable(selectedTable, orders)))) return;
    sending.current=true;setBusy(true);setError("");
    const draft: Draft = pending ?? {merchant:merchant.id,tableId:table,orderId:activeAddOnOrderId,items:lines,notes,key:newKey()};
    const submission = buildWaiterSubmission(prefix, draft);
    try {
      await SecureStore.setItemAsync(PENDING,JSON.stringify(draft));
      setPending(draft);
      const result=await client.call<{orderNumber:string;totalPesewas:number}>(submission.path,{idempotencyKey:submission.idempotencyKey,body:submission.body});
      await SecureStore.deleteItemAsync(PENDING);
      setPending(null);setLines([]);setTable("");setNotes("");setTab("track");
      setNotice((submission.isAddOn?"Added to kitchen: ":"Sent to kitchen: ")+result.orderNumber+" · "+formatMoney(result.totalPesewas));
     } catch(e) {
      if(e instanceof ApiError && TERMINAL_WAITER_SUBMIT_ERROR_CODES.has(e.code)) {
        try {await SecureStore.deleteItemAsync(PENDING);setPending(null);} catch(storageError){setError(message(storageError));}
      }
      setError(message(e)+" — retry the same order if submission remains pending.");
    }
    finally {sending.current=false;setBusy(false);}
  }
  async function action(order:Order,kind:"served"|"bill"|"close-table") {
    if(sending.current)return;
    sending.current=true;setBusy(true);setError("");
    try {
      await client.call(prefix+"/orders/"+order.orderId+"/"+kind,{method:"POST",idempotencyKey:newKey()});
      setOrders(old=>kind==="close-table"?old.filter(x=>x.orderId!==order.orderId):old.map(x=>x.orderId===order.orderId?{...x,...(kind==="served"?{kitchenStatus:"SERVED"}:{billRequested:true})}:x));
      setNotice(kind==="served"?"Marked served":kind==="bill"?"Bill requested from cashier":"Table closed; ready for cleaning");
    } catch(e){setError(message(e));} finally{sending.current=false;setBusy(false);}
  }
  const total=lines.reduce((sum,line)=>sum+(menu.find(x=>x.variantId===line.variantId)?.pricePesewas??0)*line.quantity,0);
  return <SafeAreaView style={s.root}>
    <View style={s.header}><Text style={s.heading}>ELEPAY Waiter</Text><Text style={s.white}>{merchant.tradingName}</Text><Button label="Sign out" disabled={busy||!!pending} onPress={logout}/></View>
    <View style={s.row}><Button label="New order" disabled={busy} onPress={()=>setTab("order")}/><Button label="My orders" disabled={busy} onPress={()=>setTab("track")}/></View>
    {!!error&&<Text accessibilityRole="alert" style={s.error}>{error}</Text>}
    {!!notice&&<Text accessibilityRole="alert" style={s.notice}>{notice}</Text>}
    <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
      {tab==="order"?<>
        <Text style={s.title}>Choose a table</Text>
        {!tables.length&&<Text>No tables assigned to your branch. Ask a manager.</Text>}
        {tables.map(t=><Button key={t.id} label={formatTableButtonLabel(t, table, orders)} disabled={busy||!!pending||!canSelectTable(t, orders)} onPress={()=>setTable(t.id)}/>)}
        {!!activeAddOnOrderId&&<Text style={s.notice}>Adding items to {selectedTable ? "Table " + selectedTable.tableNumber : "existing table order"} (new kitchen round)</Text>}
        <Text style={s.title}>Menu</Text>
        <TextInput accessibilityLabel="Search menu" style={s.input} value={search} onChangeText={setSearch} placeholder="Search items or category"/>
        {menu.filter(x=>(x.name+" "+(x.category??"")).toLowerCase().includes(search.toLowerCase())).map(x=><View key={x.variantId} style={s.card}>
          <Text style={s.title}>{x.name}</Text><Text>{formatMoney(x.pricePesewas)} · {x.category??"Menu"}</Text>
          <View style={s.row}><Button label="−" disabled={busy||!!pending||!lines.some(l=>l.variantId===x.variantId)} onPress={()=>change(x.variantId,-1)}/><Text>{lines.find(l=>l.variantId===x.variantId)?.quantity??0}</Text><Button label="+" disabled={busy||!!pending} onPress={()=>change(x.variantId,1)}/></View>
        </View>)}
        <Text style={s.title}>Review order</Text>
        {lines.map(l=><View key={l.variantId} style={s.card}>
          <Text>{menu.find(x=>x.variantId===l.variantId)?.name??l.variantId} × {l.quantity}</Text>
          <View style={s.courseRow}>
            {WAITER_COURSES.map(c=>{
              const active = l.course === c;
              return <Pressable key={c} accessibilityRole="button" accessibilityLabel={"Course " + c} disabled={busy||!!pending} onPress={()=>setLineCourse(l.variantId,c)} style={[s.courseChip, active&&s.courseChipActive, (busy||!!pending)&&s.disabled]}>
                <Text style={[s.courseChipText, active&&s.courseChipTextActive]}>{active?"✓ ":""}{c}</Text>
              </Pressable>;
            })}
          </View>
          <TextInput style={s.input} accessibilityLabel="Item instructions" placeholder="Item instructions" value={l.notes} maxLength={200} editable={!busy&&!pending} onChangeText={v=>setLines(old=>old.map(x=>x.variantId===l.variantId?{...x,notes:v}:x))}/>
        </View>)}
        <TextInput style={s.input} accessibilityLabel="Order notes" placeholder="Order notes" maxLength={500} value={notes} editable={!busy&&!pending} onChangeText={setNotes}/>
        <Text style={s.title}>Estimated total: {formatMoney(total)}</Text>
        <Button label={pending?"Retry same submission":busy?"Sending…":activeAddOnOrderId?"Add to table":"Send to kitchen"} disabled={!booted||storageBlocked||busy||(!pending&&(!table||!lines.length||!selectedTable||!canSelectTable(selectedTable, orders)))} onPress={()=>void submit()}/>
      </>:<>
        <Text style={s.title}>My active tables and orders</Text>
        {!orders.length&&<Text>No active orders.</Text>}
        {orders.map(o=><View key={o.orderId} style={s.card}><Text style={s.title}>Table {o.tableNumber} · {o.kitchenStatus}</Text><Text>{o.orderNumber} · {formatMoney(o.totalPesewas)} · {o.orderStatus}</Text>
          {isOrderModifiableStatus(o.orderStatus)&&<Button label="Add items" disabled={busy||!!pending||!tables.some(t=>t.currentOrderId===o.orderId)} onPress={()=>startAddItems(o)}/>}
          {o.kitchenStatus==="READY"&&<Button label="Mark food served" disabled={busy} onPress={()=>void action(o,"served")}/>}
          {o.kitchenStatus==="SERVED"&&o.orderStatus==="PAID"&&<Button label="Close paid table" disabled={busy} onPress={()=>void action(o,"close-table")}/>}
          <Button label={o.billRequested?"Bill requested":"Request bill"} disabled={busy||o.billRequested||o.orderStatus!=="PENDING"} onPress={()=>void action(o,"bill")}/>
        </View>)}
      </>}
    </ScrollView>
  </SafeAreaView>;
}

function Root() {
  const [url,setUrl]=useState("");
  const [authMode,setAuthMode]=useState<"login"|"token">("login");
  const [identifier,setIdentifier]=useState("");
  const [password,setPassword]=useState("");
  const [challengeId,setChallengeId]=useState<string|null>(null);
  const [maskedEmail,setMaskedEmail]=useState<string|null>(null);
  const [maskedPhone,setMaskedPhone]=useState<string|null>(null);
  const [emailCode,setEmailCode]=useState("");
  const [smsCode,setSmsCode]=useState("");
  const [token,setToken]=useState("");
  const [session,setSession]=useState<{url:string;token:string}|null>(null);
  const [merchant,setMerchant]=useState<MerchantView|null>(null);
  const [merchants,setMerchants]=useState<MerchantView[]>([]);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const [notice,setNotice]=useState("");
  const client=useMemo(()=>session?new ElepayClient(session.url,session.token):null,[session]);
  useEffect(()=>{let active=true;SecureStore.getItemAsync(SESSION).then(raw=>{if(active&&raw)setSession(JSON.parse(raw));}).catch(e=>setError(message(e)));return()=>{active=false;};},[]);
  useEffect(()=>{if(!client)return;let active=true;client.call<MerchantView[]>("/v1/merchants").then(rows=>{if(active){const allowed=rows.filter(x=>x.role==="waiter");setMerchants(allowed);setMerchant(allowed.length===1?allowed[0]:null);if(!allowed.length)setError("No waiter membership. Ask your manager to assign you.");}}).catch(e=>{if(active)setError(message(e));});return()=>{active=false;};},[client]);
  async function completeSession(endpoint:string,accessToken:string){
    const next={url:endpoint,token:accessToken};const c=new ElepayClient(next.url,next.token);
    const memberships=await c.call<MerchantView[]>("/v1/merchants");
    if(!memberships.some(m=>m.role==="waiter"))throw new Error("No waiter membership.");
    await SecureStore.setItemAsync(SESSION,JSON.stringify(next));setSession(next);setToken("");setPassword("");setChallengeId(null);setEmailCode("");setSmsCode("");setNotice("");
  }
  async function login(){
    if(busy)return;setBusy(true);setError("");setNotice("");
    try{
      const endpoint=url.trim().replace(/\/+$/,"");
      if(!/^https:\/\/[a-z0-9.-]+(?::\d+)?$/i.test(endpoint))throw new Error("Enter an HTTPS server origin.");
      const c=new ElepayClient(endpoint);
      if(challengeId){
        if(emailCode.trim().length!==6||smsCode.trim().length!==6)throw new Error("Enter both the 6-digit email code and 6-digit SMS code.");
        const res=await c.verifyEmailAndSms(challengeId,emailCode,smsCode);
        if(!res.verified||!res.accessToken){setNotice("One channel verified — enter the remaining code.");return;}
        await completeSession(endpoint,res.accessToken);
      }else if(authMode==="login"){
        if(!identifier.trim()||!password)throw new Error("Enter your email or phone number and password.");
        const ch=await c.loginWithPassword(identifier.trim(),password);
        setChallengeId(ch.challengeId);setMaskedEmail(ch.maskedEmail);setMaskedPhone(ch.maskedPhone);setEmailCode("");setSmsCode("");
        setNotice("Verification codes sent to your email and SMS.");
      }else{
        if(!token.trim())throw new Error("Enter a valid staff access token.");
        await completeSession(endpoint,token.trim());
      }
    }catch(e){setError(message(e));}finally{setBusy(false);}
  }
  async function resend(){
    if(!challengeId||busy)return;setBusy(true);setError("");setNotice("");
    try{const c=new ElepayClient(url.trim().replace(/\/+$/,""));await c.resendVerificationCodes(challengeId);setEmailCode("");setSmsCode("");setNotice("Fresh Email & SMS codes sent.");}
    catch(e){setError(message(e));}finally{setBusy(false);}
  }
  async function logout(){try{await SecureStore.deleteItemAsync(SESSION);setSession(null);setMerchant(null);setMerchants([]);setError("");}catch(e){Alert.alert("Sign-out failed",message(e));}}
  if(client&&merchant)return <Waiter key={merchant.id} client={client} merchant={merchant} logout={()=>void logout()}/>;
  return <SafeAreaView style={s.root}><ScrollView contentContainerStyle={s.content}><Text style={s.title}>ELEPAY Waiter</Text>{!!notice&&<Text style={s.notice}>{notice}</Text>}{!!error&&<Text style={s.error}>{error}</Text>}
    {client?<>{merchants.map(m=><Button key={m.id} label={m.tradingName} onPress={()=>setMerchant(m)}/>)}<Button label="Sign out" onPress={()=>void logout()}/></>:<>
      <TextInput style={s.input} accessibilityLabel="Server URL" autoCapitalize="none" autoCorrect={false} editable={!challengeId} placeholder="https://your-elepay-server" value={url} onChangeText={setUrl}/>
      {!challengeId&&<View style={s.row}><Button label={authMode==="login"?"Email & SMS Login ✓":"Email & SMS Login"} onPress={()=>{setAuthMode("login");setError("");}}/><Button label={authMode==="token"?"Staff Token ✓":"Staff Token"} onPress={()=>{setAuthMode("token");setError("");}}/></View>}
      {challengeId?<>
        <Text>Enter the 6-digit codes sent to {maskedEmail??"email"} and {maskedPhone??"SMS"}.</Text>
        <TextInput style={s.input} accessibilityLabel="Email code" keyboardType="number-pad" maxLength={6} placeholder="6-digit email code" value={emailCode} onChangeText={v=>setEmailCode(v.replace(/\D/g,"").slice(0,6))}/>
        <TextInput style={s.input} accessibilityLabel="SMS code" keyboardType="number-pad" maxLength={6} placeholder="6-digit SMS code" value={smsCode} onChangeText={v=>setSmsCode(v.replace(/\D/g,"").slice(0,6))}/>
        <Button label={busy?"Verifying…":"Verify & Sign in"} disabled={busy} onPress={()=>void login()}/>
        <View style={s.row}><Button label="Resend codes" disabled={busy} onPress={()=>void resend()}/><Button label="Back" disabled={busy} onPress={()=>{setChallengeId(null);setError("");setNotice("");}}/></View>
      </>:authMode==="login"?<>
        <TextInput style={s.input} accessibilityLabel="Email or phone" autoCapitalize="none" autoCorrect={false} placeholder="Email or phone (024...)" value={identifier} onChangeText={setIdentifier}/>
        <TextInput style={s.input} accessibilityLabel="Password" secureTextEntry placeholder="Password" value={password} onChangeText={setPassword}/>
        <Button label={busy?"Sending codes…":"Continue with Email & SMS Code"} disabled={busy} onPress={()=>void login()}/>
      </>:<>
        <Text>Sign in with your restaurant staff access token.</Text>
        <TextInput style={s.input} accessibilityLabel="Staff token" secureTextEntry autoCapitalize="none" autoCorrect={false} placeholder="Access token" value={token} onChangeText={setToken}/>
        <Button label={busy?"Signing in…":"Sign in"} disabled={busy} onPress={()=>void login()}/>
      </>}
    </>}
  </ScrollView></SafeAreaView>;
}
export default function App(){return <SafeAreaProvider><Root/></SafeAreaProvider>;}
const s=StyleSheet.create({root:{flex:1,backgroundColor:"#F4F7F5"},header:{padding:16,backgroundColor:"#073C2B",gap:8},heading:{fontSize:24,fontWeight:"800",color:"#fff"},white:{color:"#fff"},content:{padding:16,gap:12,paddingBottom:40},title:{fontWeight:"700",fontSize:18,color:"#14382A"},row:{flexDirection:"row",alignItems:"center",gap:12,padding:8,flexWrap:"wrap"},button:{backgroundColor:"#0A5838",padding:12,borderRadius:8,minHeight:44,justifyContent:"center"},buttonText:{color:"#fff",fontWeight:"700",textAlign:"center"},disabled:{opacity:0.45},input:{borderWidth:1,borderColor:"#B8C8BE",borderRadius:8,padding:12,backgroundColor:"#fff",color:"#14281D"},card:{padding:12,borderRadius:10,backgroundColor:"#fff",gap:8},error:{color:"#9E2020",padding:10},notice:{color:"#0A5838",padding:10,fontWeight:"700"},courseRow:{flexDirection:"row",flexWrap:"wrap",gap:6},courseChip:{paddingHorizontal:10,paddingVertical:6,borderRadius:16,borderWidth:1,borderColor:"#B8C8BE",backgroundColor:"#F4F7F5"},courseChipActive:{backgroundColor:"#0A5838",borderColor:"#0A5838"},courseChipText:{fontSize:12,fontWeight:"700",color:"#14382A"},courseChipTextActive:{color:"#fff"}});
