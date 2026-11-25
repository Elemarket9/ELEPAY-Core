import React, { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import * as SecureStore from "expo-secure-store";
import { ApiError, ElepayClient, WaiterDeviceCredential, formatMoney, newKey } from "@elepay/mobile-core";

type Table = { id: string; tableNumber: string; capacity: number; status: string; currentOrderId: string | null };
type Menu = { variantId: string; name: string; category: string | null; pricePesewas: number };
type Line = { variantId: string; quantity: number; notes: string };
type Order = { orderId: string; ticketId: string; tableNumber: string; orderNumber: string; orderStatus: string; kitchenStatus: string; subtotalPesewas: number; taxPesewas: number; totalPesewas: number; billRequested: boolean; takenBy: string; mine: boolean };
type Me = { merchantId: string; merchantName: string; branchId: string; waiter: string; taxBasisPoints: number };
type Session = { url: string; device: WaiterDeviceCredential };
type Call = <T>(path: string, options?: { method?: string; body?: unknown; idempotencyKey?: string }) => Promise<T>;
type Draft = { merchant: string; tableId: string; items: Line[]; notes: string; key: string };
const SESSION = "elepay_waiter_device";
const PENDING = "elepay_waiter_pending";
const message = (error: unknown) => error instanceof Error ? error.message : String(error);

function Button({label, onPress, disabled = false}: {label: string; onPress: () => void; disabled?: boolean}) {
  return <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={[s.button, disabled && s.disabled]}><Text style={s.buttonText}>{label}</Text></Pressable>;
}

/** Tax the server will add, for the on-screen estimate (same half-up rounding). */
function estimateTax(subtotal: number, basisPoints: number) {
  return basisPoints > 0 ? Math.round(subtotal * basisPoints / 10000) : 0;
}

function Waiter({call, me, logout}: {call: Call; me: Me; logout: () => void}) {
  const merchant = { id: me.merchantId, tradingName: me.merchantName };
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
      const draft = JSON.parse(raw) as Draft;
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
        const [t,m,o] = await Promise.all([call<Table[]>(prefix+"/tables"),call<Menu[]>(prefix+"/menu"),call<Order[]>(prefix+"/orders")]);
        if (!alive) return;
        setTables(t); setMenu(m); setOrders(o);
        const now = new Set(o.filter(x=>x.kitchenStatus==="READY").map(x=>x.orderId));
        const newlyReady = o.filter(x=>x.mine&&now.has(x.orderId)&&!ready.current.has(x.orderId));
        ready.current = now;
        if (newlyReady.length) setNotice("Food ready: " + newlyReady.map(x=>"Table "+x.tableNumber).join(", "));
      } catch(e) { if (alive) setError(message(e)); }
      finally { if (alive) timer=setTimeout(()=>void refresh(),5000); }
    }
    void refresh();
    return ()=>{alive=false;clearTimeout(timer);};
  },[call,prefix]);

  function change(id:string,delta:number) {
    if (pending || sending.current) return;
    setLines(old=>{
      const found=old.find(x=>x.variantId===id);
      if (!found) return delta>0&&old.length<100?[...old,{variantId:id,quantity:1,notes:""}]:old;
      return old.map(x=>x.variantId===id?{...x,quantity:Math.min(99,x.quantity+delta)}:x).filter(x=>x.quantity>0);
    });
  }
  async function submit() {
    if (sending.current || !booted || storageBlocked || (!pending && (!table || !lines.length))) return;
    sending.current=true;setBusy(true);setError("");
    const draft=pending??{merchant:merchant.id,tableId:table,items:lines,notes,key:newKey()};
    try {
      await SecureStore.setItemAsync(PENDING,JSON.stringify(draft));
      setPending(draft);
      const result=await call<{orderNumber:string;totalPesewas:number}>(prefix+"/orders",{idempotencyKey:draft.key,body:{tableId:draft.tableId,items:draft.items,notes:draft.notes}});
      await SecureStore.deleteItemAsync(PENDING);
      setPending(null);setLines([]);setTable("");setNotes("");setTab("track");
      setNotice("Sent to kitchen: "+result.orderNumber+" · "+formatMoney(result.totalPesewas));
     } catch(e) {
      if(e instanceof ApiError && (["invalid_order","invalid_item","table_in_use"].includes(e.code))) {
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
      await call(prefix+"/orders/"+order.orderId+"/"+kind,{method:"POST",idempotencyKey:newKey()});
      setOrders(old=>kind==="close-table"?old.filter(x=>x.orderId!==order.orderId):old.map(x=>x.orderId===order.orderId?{...x,...(kind==="served"?{kitchenStatus:"SERVED"}:{billRequested:true})}:x));
      setNotice(kind==="served"?"Marked served":kind==="bill"?"Bill requested from cashier":"Table closed; ready for cleaning");
    } catch(e){setError(message(e));} finally{sending.current=false;setBusy(false);}
  }
  const subtotal=lines.reduce((sum,line)=>sum+(menu.find(x=>x.variantId===line.variantId)?.pricePesewas??0)*line.quantity,0);
  const tax=estimateTax(subtotal,me.taxBasisPoints);
  const total=subtotal+tax;
  return <SafeAreaView style={s.root}>
    <View style={s.header}><Text style={s.heading}>ELEPAY Waiter</Text><Text style={s.white}>{merchant.tradingName}</Text><Text style={s.white}>Signed in as {me.waiter}</Text><Button label="Unpair this phone" disabled={busy||!!pending} onPress={logout}/></View>
    <View style={s.row}><Button label="New order" disabled={busy} onPress={()=>setTab("order")}/><Button label="Branch orders" disabled={busy} onPress={()=>setTab("track")}/></View>
    {!!error&&<Text accessibilityRole="alert" style={s.error}>{error}</Text>}
    {!!notice&&<Text accessibilityRole="alert" style={s.notice}>{notice}</Text>}
    <ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled">
      {tab==="order"?<>
        <Text style={s.title}>Choose a table</Text>
        {!tables.length&&<Text>No tables assigned to your branch. Ask a manager.</Text>}
        {tables.map(t=><Button key={t.id} label={(table===t.id?"✓ ":"")+"Table "+t.tableNumber+" · "+t.status+" · "+t.capacity+" seats"} disabled={busy||!!pending||t.status!=="AVAILABLE"||!!t.currentOrderId} onPress={()=>setTable(t.id)}/>)}
        <Text style={s.title}>Menu</Text>
        <TextInput accessibilityLabel="Search menu" style={s.input} value={search} onChangeText={setSearch} placeholder="Search items or category"/>
        {menu.filter(x=>(x.name+" "+(x.category??"")).toLowerCase().includes(search.toLowerCase())).map(x=><View key={x.variantId} style={s.card}>
          <Text style={s.title}>{x.name}</Text><Text>{formatMoney(x.pricePesewas)} · {x.category??"Menu"}</Text>
          <View style={s.row}><Button label="−" disabled={busy||!!pending||!lines.some(l=>l.variantId===x.variantId)} onPress={()=>change(x.variantId,-1)}/><Text>{lines.find(l=>l.variantId===x.variantId)?.quantity??0}</Text><Button label="+" disabled={busy||!!pending} onPress={()=>change(x.variantId,1)}/></View>
        </View>)}
        <Text style={s.title}>Review order</Text>
        {lines.map(l=><View key={l.variantId} style={s.card}><Text>{menu.find(x=>x.variantId===l.variantId)?.name??l.variantId} × {l.quantity}</Text><TextInput style={s.input} accessibilityLabel="Item instructions" placeholder="Item instructions" value={l.notes} maxLength={200} editable={!busy&&!pending} onChangeText={v=>setLines(old=>old.map(x=>x.variantId===l.variantId?{...x,notes:v}:x))}/></View>)}
        <TextInput style={s.input} accessibilityLabel="Order notes" placeholder="Order notes" maxLength={500} value={notes} editable={!busy&&!pending} onChangeText={setNotes}/>
        {tax>0&&<Text>Subtotal {formatMoney(subtotal)} · Tax {formatMoney(tax)}</Text>}
        <Text style={s.title}>Estimated total: {formatMoney(total)}</Text>
        <Button label={pending?"Retry same submission":busy?"Sending…":"Send to kitchen"} disabled={!booted||storageBlocked||busy||(!pending&&(!table||!lines.length))} onPress={()=>void submit()}/>
      </>:<>
        <Text style={s.title}>Active orders in your branch</Text>
        {!orders.length&&<Text>No active orders.</Text>}
        {orders.map(o=><View key={o.orderId} style={s.card}><Text style={s.title}>Table {o.tableNumber} · {o.kitchenStatus}</Text><Text>{o.orderNumber} · {formatMoney(o.totalPesewas)}{o.taxPesewas>0?" incl. tax "+formatMoney(o.taxPesewas):""} · {o.orderStatus}</Text>
          <Text>{o.mine?"Your table":"Taken by "+o.takenBy+" — you can cover it"}</Text>
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
  const [merchantCode,setMerchantCode]=useState("");
  const [code,setCode]=useState("");
  const [deviceName,setDeviceName]=useState("");
  const [session,setSession]=useState<Session|null>(null);
  const [me,setMe]=useState<Me|null>(null);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const client=useMemo(()=>session?new ElepayClient(session.url):null,[session]);
  const call=useMemo<Call|null>(()=>client&&session?(<T,>(path:string,options={})=>client.callAsWaiterDevice<T>(session.device,path,options)) as Call:null,[client,session]);
  useEffect(()=>{let active=true;SecureStore.getItemAsync(SESSION).then(raw=>{if(active&&raw)setSession(JSON.parse(raw));}).catch(e=>setError(message(e)));return()=>{active=false;};},[]);
  async function forget(reason=""){try{await SecureStore.deleteItemAsync(SESSION);}catch(e){Alert.alert("Unpair failed",message(e));return;}setSession(null);setMe(null);setError(reason);}
  useEffect(()=>{
    if(!call||!session)return;let active=true;
    call<Me>("/v1/restaurant/waiter/"+session.device.merchantId+"/me").then(m=>{if(active)setMe(m);}).catch(e=>{
      if(!active)return;
      if(e instanceof ApiError&&(e.status===401||e.status===403))void forget("This phone is no longer paired. Ask a manager for a new pairing code.");
      else setError(message(e));
    });
    return()=>{active=false;};
  },[call,session]);
  async function pair(){
    if(busy)return;setBusy(true);setError("");
    try{
      const endpoint=url.trim().replace(/\/+$/,"");
      if(!/^https:\/\/[a-z0-9.-]+(?::\d+)?$/i.test(endpoint))throw new Error("Enter the HTTPS server address your manager gave you.");
      if(!/^\d{6}$/.test(merchantCode.trim())||!/^\d{6}$/.test(code.replace(/\s/g,"")))throw new Error("The restaurant code and pairing code are both 6 digits.");
      const device=await new ElepayClient(endpoint).pairWaiterDevice(merchantCode,code,deviceName);
      const next={url:endpoint,device};
      await SecureStore.setItemAsync(SESSION,JSON.stringify(next));setSession(next);setCode("");
    }catch(e){setError(message(e));}finally{setBusy(false);}
  }
  if(call&&me)return <Waiter key={me.merchantId} call={call} me={me} logout={()=>void forget()}/>;
  return <SafeAreaView style={s.root}><ScrollView contentContainerStyle={s.content} keyboardShouldPersistTaps="handled"><Text style={s.title}>ELEPAY Waiter</Text>{!!error&&<Text accessibilityRole="alert" style={s.error}>{error}</Text>}
    {session?<><Text>Connecting to {session.device.merchantName}…</Text><Button label="Unpair this phone" onPress={()=>void forget()}/></>:<>
      <Text>Ask your manager for a pairing code for you. You only do this once on this phone.</Text>
      <TextInput style={s.input} accessibilityLabel="Server address" autoCapitalize="none" autoCorrect={false} placeholder="https://your-elepay-server" value={url} onChangeText={setUrl}/>
      <TextInput style={s.input} accessibilityLabel="Restaurant code" keyboardType="number-pad" maxLength={6} placeholder="Restaurant code (6 digits)" value={merchantCode} onChangeText={setMerchantCode}/>
      <TextInput style={s.input} accessibilityLabel="Pairing code" keyboardType="number-pad" maxLength={7} placeholder="Pairing code (6 digits)" value={code} onChangeText={setCode}/>
      <TextInput style={s.input} accessibilityLabel="Phone name" maxLength={64} placeholder="Phone name (optional)" value={deviceName} onChangeText={setDeviceName}/>
      <Button label={busy?"Pairing…":"Pair this phone"} disabled={busy} onPress={()=>void pair()}/>
    </>}
  </ScrollView></SafeAreaView>;
}
export default function App(){return <SafeAreaProvider><Root/></SafeAreaProvider>;}
const s=StyleSheet.create({root:{flex:1,backgroundColor:"#F4F7F5"},header:{padding:16,backgroundColor:"#073C2B",gap:8},heading:{fontSize:24,fontWeight:"800",color:"#fff"},white:{color:"#fff"},content:{padding:16,gap:12,paddingBottom:40},title:{fontWeight:"700",fontSize:18,color:"#14382A"},row:{flexDirection:"row",alignItems:"center",gap:12,padding:8,flexWrap:"wrap"},button:{backgroundColor:"#0A5838",padding:12,borderRadius:8,minHeight:44,justifyContent:"center"},buttonText:{color:"#fff",fontWeight:"700",textAlign:"center"},disabled:{opacity:0.45},input:{borderWidth:1,borderColor:"#B8C8BE",borderRadius:8,padding:12,backgroundColor:"#fff",color:"#14281D"},card:{padding:12,borderRadius:10,backgroundColor:"#fff",gap:8},error:{color:"#9E2020",padding:10},notice:{color:"#0A5838",padding:10,fontWeight:"700"}});
