#!/usr/bin/env python3
"""The shopping assistant's evaluation set (Phase 29), run against a live stack.

Why this exists: a unit test can prove the code around the model is right, but not that the MODEL
answers well - that depends on the model, the prompt and the data together, and changes when any of
them does. So the questions in assistant-eval.json are asked for real, through the gateway, and each
answer is checked against facts the store actually holds: the right product and price, the policy
passage it should have used, the tool it should have called, and nothing it must not reveal.

Every check is deterministic (string and structure checks on the reply), so a run is repeatable and a
failure says exactly which fact was missing. The alternative - a second model grading the first
("LLM as a judge") - can assess tone and helpfulness too, but it is itself a model that can be wrong,
and with a small local model it would be the weaker of the two.

    python3 scripts/assistant-eval.py                 # against http://localhost:8080
    BASE_URL=https://localhost:18443 SSL_CERT_FILE=.local/ecomdemo-ca.crt python3 scripts/assistant-eval.py
    python3 scripts/assistant-eval.py --report /tmp/eval.json

Exit status 0 only when every case passes. Needs only the Python standard library.
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
ADMIN_PASSWORD = os.environ.get("SMOKE_ADMIN_PASSWORD", "admin123")
PASSWORD = os.environ.get("EVAL_PASSWORD", "eval-test-password")
USERS = {"a": "eval-shopper-a", "b": "eval-shopper-b"}
DESK_MAT = 8  # seeded by catalog-service's V2 migration
HERE = os.path.dirname(os.path.abspath(__file__))


def call(method, path, token=None, body=None, timeout=180):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(BASE_URL + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw) if raw else None
        except ValueError:
            return e.code, raw.decode(errors="replace")


def login(username, password):
    status, body = call("POST", "/api/auth/login", body={"username": username, "password": password})
    if status != 200:
        sys.exit(f"login as {username} failed: {status} {body}")
    return body["accessToken"]


def setup():
    """Two customers, and an order placed by the first, whose status the second must never learn."""
    for username in USERS.values():
        status, body = call("POST", "/api/customers/register",
                            body={"username": username, "password": PASSWORD, "fullName": "Eval " + username})
        if status not in (201, 409):
            sys.exit(f"registering {username} failed: {status} {body}")
    tokens = {key: login(username, PASSWORD) for key, username in USERS.items()}

    a = tokens["a"]
    call("DELETE", f"/api/cart/items/{DESK_MAT}", a)
    status, body = call("POST", "/api/cart/items", a, {"productId": DESK_MAT, "quantity": 1})
    if status != 200:
        sys.exit(f"customer A could not add the Desk Mat to the cart: {status} {body}")
    status, order = call("POST", "/api/orders", a)
    if status != 201:
        sys.exit(f"customer A could not place an order: {status} {order}")
    order_id = order["id"]
    order_status = "PENDING"
    for _ in range(30):  # the saga settles it in a second or two
        _, body = call("GET", f"/api/orders/{order_id}/status", a)
        order_status = body["status"]
        if order_status != "PENDING":
            break
        time.sleep(1)
    return tokens, {"ORDER_A": str(order_id), "ORDER_A_STATUS": order_status.lower()}


def fill(value, placeholders):
    if isinstance(value, str):
        for key, replacement in placeholders.items():
            value = value.replace("{" + key + "}", replacement)
        return value
    if isinstance(value, list):
        return [fill(v, placeholders) for v in value]
    if isinstance(value, dict):
        return {k: fill(v, placeholders) for k, v in value.items()}
    return value


def cart_quantity(token, product_id):
    _, cart = call("GET", "/api/cart", token)
    return sum(item["quantity"] for item in cart["items"] if item["productId"] == product_id)


def evaluate(case, reply, token):
    """-> the list of failed checks (empty = pass)."""
    expect = case["expect"]
    answer = reply.get("answer", "")
    lower = answer.lower()
    sources = {f"{s['type']}:{s['id']}" for s in reply.get("sources", [])}
    tools = reply.get("toolsUsed", [])
    failures = []
    for text in expect.get("answer_contains_all", []):
        if text.lower() not in lower:
            failures.append(f"answer lacks '{text}'")
    if expect.get("answer_contains_any") and not any(t.lower() in lower for t in expect["answer_contains_any"]):
        failures.append(f"answer has none of {expect['answer_contains_any']}")
    for text in expect.get("answer_excludes", []):
        if text.lower() in lower:
            failures.append(f"answer contains '{text}'")
    for tool in expect.get("tools_include", []):
        if tool not in tools:
            failures.append(f"tool {tool} not called (called {tools})")
    for source in expect.get("sources_include", []):
        if source not in sources:
            failures.append(f"source {source} missing (got {sorted(sources)})")
    for kind in expect.get("no_sources_of_type", []):
        leaked = [s for s in sources if s.startswith(kind + ":")]
        if leaked:
            failures.append(f"{kind} sources present: {leaked}")
    wanted = expect.get("pending_action")
    if wanted:
        action = reply.get("pendingAction")
        if not action:
            failures.append("no pendingAction")
        else:
            for key, value in wanted.items():
                if action.get(key) != value:
                    failures.append(f"pendingAction.{key} is {action.get(key)!r}, expected {value!r}")
            if expect.get("cart_unchanged_until_confirmed") and not failures:
                failures += confirm_flow(action, token)
    return failures


def confirm_flow(action, token):
    """The proposal changed nothing; confirming adds it once; a second confirmation is refused."""
    failures = []
    product = action["productId"]
    before = cart_quantity(token, product)
    if before != 0:
        failures.append(f"the cart already held {before} before confirmation")
    status, _ = call("POST", f"/api/assistant/actions/{action['id']}/confirm", token)
    if status != 200:
        failures.append(f"confirmation answered {status}")
    after = cart_quantity(token, product)
    if after != action["quantity"]:
        failures.append(f"after confirming, the cart holds {after}, expected {action['quantity']}")
    status, _ = call("POST", f"/api/assistant/actions/{action['id']}/confirm", token)
    if status != 404:
        failures.append(f"a second confirmation answered {status}, expected 404")
    call("DELETE", f"/api/cart/items/{product}", token)
    return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cases", default=os.path.join(HERE, "assistant-eval.json"))
    parser.add_argument("--report", help="write the full results as JSON to this file")
    args = parser.parse_args()

    cases = json.load(open(args.cases))["cases"]
    tokens, placeholders = setup()
    call("DELETE", f"/api/cart/items/{DESK_MAT}", tokens["a"])
    print(f"Setup: customer A's order {placeholders['ORDER_A']} is {placeholders['ORDER_A_STATUS']}\n")

    results = []
    for case in cases:
        case = fill(case, placeholders)
        token = tokens[case["as"]]
        started = time.monotonic()
        status, reply = call("POST", "/api/assistant/chat", token, {"message": case["message"]})
        seconds = time.monotonic() - started
        if status != 200:
            failures = [f"HTTP {status}: {reply}"]
            reply = {}
        else:
            failures = evaluate(case, reply, token)
        results.append({"id": case["id"], "as": case["as"], "message": case["message"],
                        "passed": not failures, "failures": failures, "seconds": round(seconds, 1),
                        "answer": reply.get("answer"), "toolsUsed": reply.get("toolsUsed"),
                        "sources": reply.get("sources"), "pendingAction": reply.get("pendingAction")})
        mark = "PASS" if not failures else "FAIL"
        print(f"{mark}  {case['id']}  ({seconds:.1f}s, customer {case['as'].upper()}, tools {reply.get('toolsUsed')})")
        print(f"      Q: {case['message']}")
        print(f"      A: {(reply.get('answer') or '').strip()[:400]}")
        for failure in failures:
            print(f"      - {failure}")

    passed = sum(r["passed"] for r in results)
    print(f"\n{passed}/{len(results)} cases passed")
    if args.report:
        with open(args.report, "w") as out:
            json.dump({"baseUrl": BASE_URL, "setup": placeholders, "passed": passed,
                       "total": len(results), "results": results}, out, indent=2)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
