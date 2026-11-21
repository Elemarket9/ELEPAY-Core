from concurrent.futures import ThreadPoolExecutor
import json
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

BASE = "http://127.0.0.1:8080/sandbox"
suffix = uuid4().hex
customer = f"customer:{suffix}"
merchant_a = f"merchant:a-{suffix}"
merchant_b = f"merchant:b-{suffix}"
delivery = f"delivery:{suffix}"


def post(command, reference=None):
    reference = reference or str(uuid4())
    request = Request(
        f"{BASE}/operations/{reference}",
        data=json.dumps(command).encode(),
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    with urlopen(request, timeout=10) as result:
        assert json.load(result)["operationId"] == reference
    return reference


def balance(account):
    with urlopen(f"{BASE}/balances/{account}", timeout=10) as result:
        return json.load(result)["pesewas"]


funding = dict(type="topup", account=customer, pesewas=15000)
reference = post(funding)
post(funding, reference)
assert balance(customer) == 15000
checkout = post(dict(type="checkout", account=customer, allocations=[
    dict(recipient=merchant_a, pesewas=8000),
    dict(recipient=merchant_b, pesewas=5000),
    dict(recipient=delivery, pesewas=2000),
]))
assert [balance(account) for account in (customer, merchant_a, merchant_b, delivery)] == [0, 8000, 5000, 2000]
post(dict(type="refund", checkout=checkout, line=1, pesewas=5000))
try:
    post(dict(type="refund", checkout=checkout, line=1, pesewas=1))
except HTTPError as error:
    assert error.code == 409
else:
    raise AssertionError("An excessive refund was accepted")
assert balance(customer) == 5000
assert balance(merchant_b) == 0
post(dict(type="withdraw", account=merchant_a, pesewas=8000))
post(dict(type="withdraw", account=delivery, pesewas=2000))
post(dict(type="cashout", account=customer, pesewas=5000))
assert all(balance(account) == 0 for account in (customer, merchant_a, merchant_b, delivery))
print("Wallet checkout, replay, refund and withdrawals passed")

post(funding)
def try_checkout(recipient):
    try:
        post(dict(type="checkout", account=customer, allocations=[
            dict(recipient=recipient, pesewas=15000),
        ]))
        return True
    except HTTPError as error:
        assert error.code == 409
        return False

with ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(try_checkout, (merchant_a, merchant_b)))
assert sorted(results) == [False, True]
assert balance(customer) == 0
assert balance(merchant_a) + balance(merchant_b) == 15000
print("Concurrent checkouts cannot spend the same wallet balance twice")
