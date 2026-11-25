import React, { useEffect, useMemo, useRef, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import * as SecureStore from "expo-secure-store";
import { ApiError, ElepayClient, MerchantView, formatMoney, newKey } from "@elepay/mobile-core";

type Table = { id: string; tableNumber: string; capacity: number; status: string; currentOrderId: string | null };
type Menu = { variantId: string; name: string; category: string | null; pricePesewas: number };
type Line = { variantId: string; quantity: number; notes: string };
type Order = { orderId: string; ticketId: string; tableNumber: string; orderNumber: string; orderStatus: string; kitchenStatus: string; totalPesewas: number; billRequested: boolean };
type Draft = { merchant: string; tableId: string; items: Line[]; notes: string; key: string };
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
      const result=await client.call<{orderNumber:string;totalPesewas:number}>(prefix+"/orders",{idempotencyKey:draft.key,body:{tableId:draft.tableId,items:draft.items,notes:draft.notes}});
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
        <Text style={s.title}>Estimated total: {formatMoney(total)}</Text>
        <Button label={pending?"Retry same submission":busy?"Sending…":"Send to kitchen"} disabled={!booted||storageBlocked||busy||(!pending&&(!table||!lines.length))} onPress={()=>void submit()}/>
      </>:<>
        <Text style={s.title}>My active tables and orders</Text>
        {!orders.length&&<Text>No active orders.</Text>}
        {orders.map(o=><View key={o.orderId} style={s.card}><Text style={s.title}>Table {o.tableNumber} · {o.kitchenStatus}</Text><Text>{o.orderNumber} · {formatMoney(o.totalPesewas)} · {o.orderStatus}</Text>
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
  const [token,setToken]=useState("");
  const [session,setSession]=useState<{url:string;token:string}|null>(null);
  const [merchant,setMerchant]=useState<MerchantView|null>(null);
  const [merchants,setMerchants]=useState<MerchantView[]>([]);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState("");
  const client=useMemo(()=>session?new ElepayClient(session.url,session.token):null,[session]);
  useEffect(()=>{let active=true;SecureStore.getItemAsync(SESSION).then(raw=>{if(active&&raw)setSession(JSON.parse(raw));}).catch(e=>setError(message(e)));return()=>{active=false;};},[]);
  useEffect(()=>{if(!client)return;let active=true;client.call<MerchantView[]>("/v1/merchants").then(rows=>{if(active){const allowed=rows.filter(x=>x.role==="waiter");setMerchants(allowed);setMerchant(allowed.length===1?allowed[0]:null);if(!allowed.length)setError("No waiter membership. Ask your manager to assign you.");}}).catch(e=>{if(active)setError(message(e));});return()=>{active=false;};},[client]);
  async function login(){
    if(busy)return;setBusy(true);setError("");
    try{
      const endpoint=url.trim().replace(/\/+$/,"");
      if(!/^https:\/\/[a-z0-9.-]+(?::\d+)?$/i.test(endpoint)||!token.trim())throw new Error("Enter an HTTPS server origin and a valid staff access token.");
      const next={url:endpoint,token:token.trim()};const c=new ElepayClient(next.url,next.token);
      const memberships=await c.call<MerchantView[]>("/v1/merchants");
      if(!memberships.some(m=>m.role==="waiter"))throw new Error("No waiter membership.");
      await SecureStore.setItemAsync(SESSION,JSON.stringify(next));setSession(next);setToken("");
    }catch(e){setError(message(e));}finally{setBusy(false);}
  }
  async function logout(){try{await SecureStore.deleteItemAsync(SESSION);setSession(null);setMerchant(null);setMerchants([]);setError("");}catch(e){Alert.alert("Sign-out failed",message(e));}}
  if(client&&merchant)return <Waiter key={merchant.id} client={client} merchant={merchant} logout={()=>void logout()}/>;
  return <SafeAreaView style={s.root}><ScrollView contentContainerStyle={s.content}><Text style={s.title}>ELEPAY Waiter</Text>{!!error&&<Text style={s.error}>{error}</Text>}
    {client?<>{merchants.map(m=><Button key={m.id} label={m.tradingName} onPress={()=>setMerchant(m)}/>)}<Button label="Sign out" onPress={()=>void logout()}/></>:<>
      <Text>Sign in with your restaurant staff access token.</Text><TextInput style={s.input} accessibilityLabel="Server URL" autoCapitalize="none" autoCorrect={false} placeholder="https://your-elepay-server" value={url} onChangeText={setUrl}/>
      <TextInput style={s.input} accessibilityLabel="Staff token" secureTextEntry autoCapitalize="none" autoCorrect={false} placeholder="Access token" value={token} onChangeText={setToken}/>
      <Button label={busy?"Signing in…":"Sign in"} disabled={busy} onPress={()=>void login()}/>
    </>}
  </ScrollView></SafeAreaView>;
}
export default function App(){return <SafeAreaProvider><Root/></SafeAreaProvider>;}
const s=StyleSheet.create({root:{flex:1,backgroundColor:"#F4F7F5"},header:{padding:16,backgroundColor:"#073C2B",gap:8},heading:{fontSize:24,fontWeight:"800",color:"#fff"},white:{color:"#fff"},content:{padding:16,gap:12,paddingBottom:40},title:{fontWeight:"700",fontSize:18,color:"#14382A"},row:{flexDirection:"row",alignItems:"center",gap:12,padding:8,flexWrap:"wrap"},button:{backgroundColor:"#0A5838",padding:12,borderRadius:8,minHeight:44,justifyContent:"center"},buttonText:{color:"#fff",fontWeight:"700",textAlign:"center"},disabled:{opacity:0.45},input:{borderWidth:1,borderColor:"#B8C8BE",borderRadius:8,padding:12,backgroundColor:"#fff",color:"#14281D"},card:{padding:12,borderRadius:10,backgroundColor:"#fff",gap:8},error:{color:"#9E2020",padding:10},notice:{color:"#0A5838",padding:10,fontWeight:"700"}});
