#!/usr/bin/env python3
"""
MTN Ghana MobileMoney Limited (MML) Open API UAT Testcase Runner.
Executes the 5 official sheets from 'MTN MoMo OpenAPI_UAT Testcases .xlsx':
  1. Authentication (OAuth 2.0 valid/invalid sub key, invalid API key, token refresh)
  2. Collection (RequestToPay approve, reject, timeout, duplicate ref, incomplete, invalid sub/env/token, GET status)
  3. Disbursement (Transfer success, duplicate ref, incomplete, invalid sub/env/token, GET status)
  4. ValidateAccount (accountholder/msisdn/{msisdn}/active valid & negative cases)
  5. Balance (account/balance valid & negative cases)

Usage:
  MTN_BASE_URL=https://sandbox.momodeveloper.mtn.com \
  MTN_TARGET_ENV=sandbox \
  MTN_COLLECTION_SUB_KEY=... MTN_COLLECTION_USER=... MTN_COLLECTION_KEY=... \
  MTN_DISBURSEMENT_SUB_KEY=... MTN_DISBURSEMENT_USER=... MTN_DISBURSEMENT_KEY=... \
  python3 scripts/mtn_uat_runner.py --out mtn_uat_results.csv
"""
import argparse
import base64
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone


def http_call(method, url, headers=None, body=None, timeout=15):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req_headers = {"Accept": "application/json", "User-Agent": "ELEPAY-UAT-Runner/1.0"}
    if data is not None:
        req_headers["Content-Type"] = "application/json"
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return resp.status, raw
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8", errors="replace")
        return err.code, raw
    except Exception as exc:
        return 0, str(exc)


def get_token(base_url, product, user, key, sub_key):
    basic = base64.b64encode(f"{user}:{key}".encode("utf-8")).decode("ascii")
    status, body = http_call(
        "POST",
        f"{base_url}/{product}/token/",
        headers={"Authorization": f"Basic {basic}", "Ocp-Apim-Subscription-Key": sub_key},
    )
    if status == 200:
        try:
            tok = json.loads(body).get("access_token")
            return (status, tok) if tok else (status, None)
        except Exception:
            return status, None
    return status, None


def poll_final_status(url, headers, expected_statuses, timeout_s=45, interval_s=5):
    """Polls an MTN async status endpoint until a terminal status is reached or timeout expires."""
    deadline = time.monotonic() + timeout_s
    last_status = 0
    last_body = ""
    while True:
        last_status, last_body = http_call("GET", url, headers=headers)
        if last_status == 200:
            try:
                parsed = json.loads(last_body)
                tx_status = str(parsed.get("status", "")).upper()
                if tx_status in expected_statuses:
                    return True, last_status, last_body[:160]
                if tx_status and tx_status != "PENDING":
                    return False, last_status, f"unexpected status={tx_status} body={last_body[:120]}"
            except Exception:
                pass
        if time.monotonic() >= deadline:
            return False, last_status, f"timeout waiting for {expected_statuses}: {last_body[:120]}"
        time.sleep(interval_s)


def write_csv(out_path, results):
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=["Sheet", "TC#", "Test Case", "Result", "HTTP Status", "ReferenceID", "Notes", "ExecutedAt"],
        )
        writer.writeheader()
        writer.writerows(results)


def main():
    parser = argparse.ArgumentParser(description="Run MTN MoMo OpenAPI UAT Testcases")
    parser.add_argument("--out", default="mtn_uat_results.csv", help="Output CSV path")
    args = parser.parse_args()

    base = os.environ.get("MTN_BASE_URL", "https://sandbox.momodeveloper.mtn.com").rstrip("/")
    env = os.environ.get("MTN_TARGET_ENV", "sandbox")
    currency = "EUR" if env == "sandbox" else "GHS"

    col_sub = os.environ.get("MTN_COLLECTION_SUB_KEY", "")
    col_user = os.environ.get("MTN_COLLECTION_USER", "")
    col_key = os.environ.get("MTN_COLLECTION_KEY", "")
    dis_sub = os.environ.get("MTN_DISBURSEMENT_SUB_KEY", "")
    dis_user = os.environ.get("MTN_DISBURSEMENT_USER", "")
    dis_key = os.environ.get("MTN_DISBURSEMENT_KEY", "")

    if not all([col_sub, col_user, col_key, dis_sub, dis_user, dis_key]):
        print("ERROR: Set MTN_COLLECTION_* and MTN_DISBURSEMENT_* environment variables.", file=sys.stderr)
        sys.exit(2)

    results = []

    def record(sheet, tc, name, passed, http_status, ref_id="", notes=""):
        status_str = "PASS" if passed else "FAIL"
        results.append({
            "Sheet": sheet,
            "TC#": tc,
            "Test Case": name,
            "Result": status_str,
            "HTTP Status": http_status,
            "ReferenceID": ref_id,
            "Notes": notes,
            "ExecutedAt": datetime.now(timezone.utc).isoformat(),
        })
        print(f"[{status_str}] {sheet} {tc} — {name} (HTTP {http_status}) {ref_id}")

    exit_code = 1
    try:
        # --- Sheet 1 & 2: Collection Auth + Transactions ---
        st, _ = get_token(base, "collection", col_user, col_key, "invalid-sub-key")
        record("Collection", "TC01-01", "OAuth Invalid Subscription Key", st in (401, 403), st)

        st, _ = get_token(base, "collection", col_user, "invalid-api-key", col_sub)
        record("Collection", "TC01-02", "OAuth Invalid API Key", st == 401, st)

        st, col_token = get_token(base, "collection", col_user, col_key, col_sub)
        record("Collection", "TC01-03", "OAuth Valid Token", st == 200 and bool(col_token), st)
        if not col_token:
            print("ERROR: Failed to obtain valid Collection OAuth token; aborting.", file=sys.stderr)
            exit_code = 2
            return

        st2, col_token2 = get_token(base, "collection", col_user, col_key, col_sub)
        record("Collection", "TC01-04", "OAuth Second Token Before Expiry", st2 == 200 and bool(col_token2), st2)

        def rtp(ref_id, msisdn, amount="5.00", cur=currency, sub=col_sub, target=env, tok=col_token, omit_amount=False):
            payload = {
                "currency": cur,
                "externalId": f"uat-{ref_id[:8]}",
                "payer": {"partyIdType": "MSISDN", "partyId": msisdn},
                "payerMessage": "ELEPAY UAT",
                "payeeNote": "ELEPAY UAT",
            }
            if not omit_amount:
                payload["amount"] = amount
            return http_call(
                "POST",
                f"{base}/collection/v1_0/requesttopay",
                headers={
                    "Authorization": f"Bearer {tok}",
                    "X-Reference-Id": ref_id,
                    "X-Target-Environment": target,
                    "Ocp-Apim-Subscription-Key": sub,
                },
                body=payload,
            )

        col_get_headers = {"Authorization": f"Bearer {col_token}", "X-Target-Environment": env, "Ocp-Apim-Subscription-Key": col_sub}

        ok_ref = str(uuid.uuid4())
        st, body = rtp(ok_ref, "233240000001")
        if st == 202:
            ok_final, gst, gnotes = poll_final_status(f"{base}/collection/v1_0/requesttopay/{ok_ref}", col_get_headers, {"SUCCESSFUL"})
            record("Collection", "TC02-01", "RequestToPay Approve", ok_final, gst, ok_ref, gnotes)
        else:
            record("Collection", "TC02-01", "RequestToPay Approve", False, st, ok_ref, body[:160])

        rej_ref = str(uuid.uuid4())
        st, body = rtp(rej_ref, "46733123451")  # MTN Sandbox rejected test number
        if st == 202:
            rej_final, gst, gnotes = poll_final_status(f"{base}/collection/v1_0/requesttopay/{rej_ref}", col_get_headers, {"FAILED", "REJECTED"})
            record("Collection", "TC02-02", "RequestToPay Reject", rej_final, gst, rej_ref, gnotes)
        else:
            record("Collection", "TC02-02", "RequestToPay Reject", False, st, rej_ref, body[:160])

        to_ref = str(uuid.uuid4())
        st, body = rtp(to_ref, "46733123452")  # MTN Sandbox timeout test number
        if st == 202:
            to_final, gst, gnotes = poll_final_status(f"{base}/collection/v1_0/requesttopay/{to_ref}", col_get_headers, {"FAILED", "EXPIRED", "TIMEOUT"})
            record("Collection", "TC02-03", "RequestToPay Timeout", to_final, gst, to_ref, gnotes)
        else:
            record("Collection", "TC02-03", "RequestToPay Timeout", False, st, to_ref, body[:160])

        st, body = rtp(ok_ref, "233240000001")
        record("Collection", "TC02-04", "RequestToPay Duplicate Reference ID", st == 409, st, ok_ref, body[:120])

        inc_ref = str(uuid.uuid4())
        st, body = rtp(inc_ref, "233240000001", omit_amount=True)
        record("Collection", "TC02-05", "RequestToPay Incomplete Information", st == 400, st, inc_ref, body[:120])

        st, body = rtp(str(uuid.uuid4()), "233240000001", sub="invalid-sub")
        record("Collection", "TC02-09", "RequestToPay Invalid Subscription Key", st in (401, 403), st, "", body[:120])

        st, body = rtp(str(uuid.uuid4()), "233240000001", target="invalid-env")
        record("Collection", "TC02-10", "RequestToPay Invalid Target Environment", st in (400, 403), st, "", body[:120])

        st, body = rtp(str(uuid.uuid4()), "233240000001", tok="invalid-token")
        record("Collection", "TC02-11", "RequestToPay Invalid OAuth Token", st == 401, st, "", body[:120])

        st, body = http_call("GET", f"{base}/collection/v1_0/requesttopay/{ok_ref}", headers=col_get_headers)
        record("Collection", "TC02-12", "GET RequestToPay Status (Valid Ref)", st == 200, st, ok_ref, body[:120])

        st, body = http_call("GET", f"{base}/collection/v1_0/requesttopay/{uuid.uuid4()}", headers=col_get_headers)
        record("Collection", "TC02-13", "GET RequestToPay Status (Invalid Ref)", st == 404, st, "", body[:120])

        # --- Sheet 3: Disbursement Auth + Transfer ---
        st, dis_token = get_token(base, "disbursement", dis_user, dis_key, dis_sub)
        record("Disbursement", "TC01-03", "Disbursement OAuth Valid Token", st == 200 and bool(dis_token), st)
        if not dis_token:
            print("ERROR: Failed to obtain valid Disbursement OAuth token; aborting.", file=sys.stderr)
            exit_code = 2
            return

        def transfer(ref_id, msisdn="233240000001", amount="5.00", sub=dis_sub, target=env, tok=dis_token, omit_amount=False):
            payload = {
                "currency": currency,
                "externalId": f"uat-dis-{ref_id[:8]}",
                "payee": {"partyIdType": "MSISDN", "partyId": msisdn},
                "payerMessage": "ELEPAY UAT Payout",
                "payeeNote": "ELEPAY UAT Payout",
            }
            if not omit_amount:
                payload["amount"] = amount
            return http_call(
                "POST",
                f"{base}/disbursement/v1_0/transfer",
                headers={
                    "Authorization": f"Bearer {tok}",
                    "X-Reference-Id": ref_id,
                    "X-Target-Environment": target,
                    "Ocp-Apim-Subscription-Key": sub,
                },
                body=payload,
            )

        dis_get_headers = {"Authorization": f"Bearer {dis_token}", "X-Target-Environment": env, "Ocp-Apim-Subscription-Key": dis_sub}

        dis_ref = str(uuid.uuid4())
        st, body = transfer(dis_ref)
        if st == 202:
            dis_final, gst, gnotes = poll_final_status(f"{base}/disbursement/v1_0/transfer/{dis_ref}", dis_get_headers, {"SUCCESSFUL"})
            record("Disbursement", "TC02-01", "Transfer Valid", dis_final, gst, dis_ref, gnotes)
        else:
            record("Disbursement", "TC02-01", "Transfer Valid", False, st, dis_ref, body[:160])

        st, body = transfer(dis_ref)
        record("Disbursement", "TC02-04", "Transfer Duplicate Reference ID", st == 409, st, dis_ref, body[:120])

        st, body = transfer(str(uuid.uuid4()), omit_amount=True)
        record("Disbursement", "TC02-05", "Transfer Incomplete Information", st == 400, st, "", body[:120])

        st, body = transfer(str(uuid.uuid4()), sub="invalid-sub")
        record("Disbursement", "TC02-07", "Transfer Invalid Subscription Key", st in (401, 403), st, "", body[:120])

        st, body = transfer(str(uuid.uuid4()), target="invalid-env")
        record("Disbursement", "TC02-08", "Transfer Invalid Target Environment", st in (400, 403), st, "", body[:120])

        st, body = transfer(str(uuid.uuid4()), tok="invalid-token")
        record("Disbursement", "TC02-09", "Transfer Invalid OAuth Token", st == 401, st, "", body[:120])

        st, body = http_call("GET", f"{base}/disbursement/v1_0/transfer/{dis_ref}", headers=dis_get_headers)
        record("Disbursement", "TC02-10", "GET Transfer Status (Valid Ref)", st == 200, st, dis_ref, body[:120])

        # --- Sheet 4: ValidateAccount ---
        st, body = http_call(
            "GET",
            f"{base}/collection/v1_0/accountholder/msisdn/233240000001/active",
            headers=col_get_headers,
        )
        record("ValidateAccount", "TC02-01", "Validate Active MSISDN", st == 200, st, "", body[:100])

        st, _ = http_call(
            "GET",
            f"{base}/collection/v1_0/accountholder/msisdn/233240000001/active",
            headers={"Authorization": "Bearer invalid-token", "X-Target-Environment": env, "Ocp-Apim-Subscription-Key": col_sub},
        )
        record("ValidateAccount", "TC02-06", "Validate Account Invalid Token", st == 401, st)

        # --- Sheet 5: Balance ---
        st, body = http_call("GET", f"{base}/collection/v1_0/account/balance", headers=col_get_headers)
        record("Balance", "TC02-01", "GET Collection Account Balance", st == 200, st, "", body[:100])

        st, body = http_call("GET", f"{base}/disbursement/v1_0/account/balance", headers=dis_get_headers)
        record("Balance", "TC02-01b", "GET Disbursement Account Balance", st == 200, st, "", body[:100])

        st, _ = http_call(
            "GET",
            f"{base}/collection/v1_0/account/balance",
            headers={"Authorization": "Bearer invalid-token", "X-Target-Environment": env, "Ocp-Apim-Subscription-Key": col_sub},
        )
        record("Balance", "TC02-06", "GET Balance Invalid Token", st == 401, st)

        passed = sum(1 for r in results if r["Result"] == "PASS")
        exit_code = 0 if passed == len(results) else 1
    finally:
        write_csv(args.out, results)
        passed = sum(1 for r in results if r["Result"] == "PASS")
        print(f"\nCompleted {len(results)} UAT checks: {passed}/{len(results)} PASS. Report written to {args.out}")

    sys.exit(exit_code)


if __name__ == "__main__":
    main()
