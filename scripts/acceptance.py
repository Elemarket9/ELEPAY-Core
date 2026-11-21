"""End-to-end acceptance journey against a running ELEPAY service.

Run the service with the sandbox and dev profiles (simulated rails, dev tokens):
    java -jar target/elepay-core-*.jar --spring.profiles.active=sandbox,dev
    python3 scripts/acceptance.py

Every money movement here uses SIMULATED rails; nothing is a live result.
"""
import json
import os
import sys
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen

BASE = os.environ.get("ELEPAY_URL", "http://127.0.0.1:8080")
RUN = uuid.uuid4().hex[:8]


def call(method, path, token=None, body=None, key=None, expect=200):
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["Idempotency-Key"] = key
    data = None if body is None else json.dumps(body).encode()
    request = Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=20) as response:
            status, payload = response.status, response.read()
    except HTTPError as error:
        status, payload = error.code, error.read()
    parsed = json.loads(payload) if payload else None
    if expect is not None and status != expect:
        raise AssertionError(f"{method} {path}: expected {expect}, got {status}: {parsed}")
    return parsed


def token(subject, *roles):
    return call("POST", "/dev/token", body={"subject": subject, "roles": list(roles)})["accessToken"]


def phone():
    return "233" + str(200000000 + uuid.uuid4().int % 700000000)


ops = token(f"ops-{RUN}", "ops")


def merchant(owner, name, kind, fee_bps):
    m = call("POST", "/v1/merchants", owner, {"legalName": name, "tradingName": name, "kind": kind,
                                              "contactPhone": "0241234567"})
    call("POST", f"/v1/ops/merchants/{m['id']}/review", ops, {"decision": "approve", "feeBps": fee_bps})
    return m


def balances(owner, merchant_id):
    return call("GET", f"/v1/merchants/{merchant_id}/balances", owner)


# ---- recipient name confirmation -----------------------------------------------------------
sender_subject, recipient_phone = f"sender-{RUN}", phone()
call("POST", "/v1/ops/customers", ops, {"subject": sender_subject, "phone": phone(), "displayName": "Kofi Sender"})
call("POST", "/v1/ops/customers", ops, {"subject": f"recipient-{RUN}", "phone": recipient_phone, "displayName": "Ama Mensah"})
sender = token(sender_subject)
local = call("POST", "/v1/wallet/recipients/lookup", sender, {"rail": "wallet", "phone": "0" + recipient_phone[3:]})
intl = call("POST", "/v1/wallet/recipients/lookup", sender, {"rail": "wallet", "phone": "+" + recipient_phone})
assert local["verifiedName"] == intl["verifiedName"] == "Ama Mensah"
print("Name confirmation: local and +233 formats show the same verified name")

# ---- fund GH₵150 through a top-up and confirm through rail recovery --------------------------
topup = call("POST", "/v1/wallet/topups", sender, {"amount": 15000, "method": "momo", "phone": "0245550005",
                                                   "network": "mtn"}, key=str(uuid.uuid4()))
assert topup["status"] == "pending"
call("POST", "/v1/ops/rails/recover", ops)
wallet = call("GET", "/v1/wallet", sender)
available = next(a["balance"] for a in wallet["accounts"] if a["bucket"] == "available")
assert available == 15000, available
print("Funded GH₵150 via simulated MoMo top-up")

# ---- marketplace checkout --------------------------------------------------------------------
owners = {k: token(f"{k}-{RUN}") for k in ("mp", "a", "b", "d")}
mp = merchant(owners["mp"], f"ELEMARKET {RUN}", "marketplace", 0)
a = merchant(owners["a"], f"Seller A {RUN}", "merchant", 150)
b = merchant(owners["b"], f"Seller B {RUN}", "merchant", 100)
d = merchant(owners["d"], f"ELESEND {RUN}", "delivery", 0)
for seller, owner in ((a, owners["a"]), (b, owners["b"]), (d, owners["d"])):
    call("POST", f"/v1/merchants/{mp['id']}/sellers", owners["mp"], {"merchantCode": seller["merchantCode"]})
    call("POST", f"/v1/merchants/{seller['id']}/marketplaces/{mp['id']}/accept", owner)
order = call("POST", f"/v1/merchants/{mp['id']}/checkouts", owners["mp"], {
    "orderReference": f"ELM-{RUN}",
    "allocations": [{"merchantId": a["id"], "kind": "sale", "amount": 8000},
                    {"merchantId": b["id"], "kind": "sale", "amount": 5000},
                    {"merchantId": d["id"], "kind": "delivery", "amount": 2000}]}, key=str(uuid.uuid4()))
reference = order["reference"]
pay_key = str(uuid.uuid4())
first = call("POST", f"/v1/checkouts/{reference}/pay/wallet", sender, key=pay_key)
assert call("POST", f"/v1/checkouts/{reference}/pay/wallet", sender, key=pay_key) == first
call("POST", f"/v1/checkouts/{reference}/pay/wallet", sender, key=str(uuid.uuid4()), expect=409)
print("Paid GH₵150 once across seller A, seller B and delivery; duplicate and repeat payments refused")

call("GET", f"/v1/merchants/{a['id']}/balances", owners["b"], expect=404)
call("POST", f"/v1/merchants/{b['id']}/checkouts/{reference}/allocations/1/refunds", owners["b"],
     {"amount": 5000, "reason": "Seller cancelled"}, key=str(uuid.uuid4()))
call("POST", "/v1/ops/settlements/run", ops, {"ignoreDelay": True, "merchantId": a["id"]})
call("POST", "/v1/ops/settlements/run", ops, {"ignoreDelay": True, "merchantId": d["id"]})
assert balances(owners["a"], a["id"])["available"] == 7880
assert balances(owners["d"], d["id"])["available"] == 2000
assert balances(owners["b"], b["id"])["available"] == 0
wallet = call("GET", "/v1/wallet", sender)
assert next(x["balance"] for x in wallet["accounts"] if x["bucket"] == "available") == 5000
print("Refunded seller B's GH₵50, settled A (GH₵78.80 after agreed fee) and delivery (GH₵20)")

# ---- withdraw settled proceeds ---------------------------------------------------------------
for m, owner, amount in ((a, owners["a"], 7880), (d, owners["d"], 2000)):
    dest = call("POST", f"/v1/merchants/{m['id']}/settlement-destinations", owner,
                {"type": "momo", "network": "mtn", "accountNumber": "0245550005", "accountName": m["tradingName"]})
    call("POST", f"/v1/merchants/{m['id']}/withdrawals", owner, {"amount": amount, "destinationId": dest["id"]},
         key=str(uuid.uuid4()))
call("POST", "/v1/ops/rails/recover", ops)
assert balances(owners["a"], a["id"]) == {"merchantId": a["id"], "currency": "GHS", "pending": 0, "available": 0, "reserved": 0}
assert balances(owners["d"], d["id"])["available"] == 0
integrity = call("GET", "/v1/ops/ledger/integrity", ops)
assert integrity["accountsWithDrift"] == 0 and integrity["unbalancedTransactions"] == 0, integrity
assert all(v == 0 for v in integrity["netByCurrency"].values()), integrity
print("Withdrew all merchant and delivery proceeds; ledger reconciles to zero in every currency")
print("Acceptance journey passed (simulated rails)")
sys.exit(0)
