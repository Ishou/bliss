# Incident 2026-05-10: Worker floating-IP alias wiped, public 80/443 dark

## Summary

The Hetzner Cloud Floating IP (`116.202.180.82`) lost its alias on the
worker's `eth0` interface. The k3s API server on the same FIP at port
6443 stayed reachable (via an iptables PREROUTING DNAT rule that
doesn't require local IP ownership), so every cluster-internal signal
read healthy. The public surface — `api.wordsparrow.io`,
`errors.wordsparrow.io`, `signoz.wordsparrow.io`,
`matomo.wordsparrow.io` — was unreachable on 80/443. Detection came
from the operator. Mitigation was a `systemctl restart
floating-ip-config.service` on the worker, which re-ran the runtime
`ip addr add` step. Permanent fix is ADR-0035 (declare the alias in
netplan so reapplies preserve it).

## Impact

- **Public surface down:** all hostnames pointing at the FIP via
  Cloudflare DNS (`api.wordsparrow.io` and four `*.wordsparrow.io`
  admin subdomains) returned connection timeouts on 80/443 from the
  public internet.
- **Cloudflare-Pages-served `wordsparrow.io` (the app frontend) was
  unaffected** — Pages serves from Cloudflare's edge, not Hetzner.
- **Outage window:** ~9 minutes minimum. Last successful inbound
  request to the ingress controller was 07:18:42 UTC (an internet
  scanner). Service restored at 07:27 UTC after operator intervention.
  No paying users impacted (no traffic complaints; scanner-only
  baseline at outage time).
- **k3s control plane stayed healthy throughout** — `kubectl` on the
  FIP worked the entire time because the iptables 6443 DNAT rule
  fires before the kernel checks IP locality.

## Timeline (UTC, 2026-05-10)

- **~07:18** — Last externally-observed request hits the ingress
  controller (per pod logs).
- **~07:18 → 07:27** — FIP-destined traffic on 80/443 is silently
  dropped by the worker kernel. iptables-NAT'd 6443 traffic continues
  to flow.
- **~07:24** — Operator notices "production is down, whole Hetzner is
  timing out" (initial misattribution).
- **07:24** — Diagnosis pivots after `https://wordsparrow.io/`
  (Cloudflare Pages) returns 200 while `api.wordsparrow.io`
  (Hetzner FIP) times out.
- **07:25** — `kubectl get nodes` succeeds against the same FIP →
  rules out FIP-level outage, narrows scope.
- **07:26** — Direct probes of the worker's primary public IP
  (`178.105.38.131`) on 80/443 return immediately (ingress healthy).
  Probes of the FIP on 80/443 time out, while the FIP on 6443
  responds. Conclusion: FIP alias missing on worker `eth0`.
- **07:27** — Operator runs:
  ```
  ssh root@178.105.38.131 'systemctl restart floating-ip-config.service && \
    ip -4 addr show eth0 | grep 116.202.180.82'
  ```
  Service restored within seconds. `api.wordsparrow.io/health` returns
  404 (correct — no such route) and `errors.wordsparrow.io` returns
  302 (oauth2-proxy redirect).

## Root cause

The FIP alias `ip addr add 116.202.180.82/32 dev eth0` was applied at
boot by `floating-ip-config.service` (a `oneshot RemainAfterExit=yes`
systemd unit, see `terraform/k8s/providers/hetzner/cloud-init/worker.yaml.tftpl`
prior to ADR-0035). It was **runtime-only state** — not declared in
any netplan or systemd-networkd configuration file. Any process that
reapplies the netplan config wipes secondary `/32` addresses that
weren't declared there: DHCP renewal cycling `eth0`, package upgrade
rewriting `/etc/netplan/`, manual `netplan apply`, cloud-init's
`network` module re-running on a metadata refresh.

The exact trigger was not captured (we did not run `journalctl --since
06:00 -u systemd-networkd -u networkd-dispatcher -u cloud-init`
before restarting the service, which would have shown the reapply
event in the worker's own logs). For prevention purposes the trigger
doesn't matter: any reapply of any kind kills the alias.

## What stopped this from being detected sooner

- **No external blackbox probe.** Detection relied on the operator
  noticing. We have nothing outside the cluster periodically hitting
  `https://api.wordsparrow.io/health` and alerting on failure — and
  any alerting that runs *inside* the cluster (SigNoz uptime, etc.)
  would itself be unreachable through the same broken ingress.
- **Cluster-internal signals all read green.** `kubectl get nodes`
  worked, ingress pods were `Running 1/1`, the controller's ClusterIP
  service had endpoints. The asymmetry between "6443 NAT works
  without local IP ownership" and "80/443 require it" makes this
  failure mode invisible to anything that probes via 6443 or via
  the cluster API.
- **No alert on "ingress receiving zero external requests."** The
  most direct symptom — request rate at the ingress dropped to 0 —
  is exactly what we should have alerted on.

## Resolution

Manual: `systemctl restart floating-ip-config.service` on the worker,
which re-ran the script's `ip addr add` step. This restored service
in seconds but did not address the underlying fragility.

Permanent: ADR-0035 — declare the alias in
`/etc/netplan/60-floating-ip.yaml` (a netplan dropin) so any future
netplan reapply preserves it. The systemd unit is reduced in scope
to the iptables NAT rules. A `*.tftest.hcl` regression test pins the
new shape.

## Action items

1. **[done in this PR]** Move the FIP alias to a netplan dropin in
   the worker cloud-init template — ADR-0035, this PR.
2. **[done in this PR]** Regression test
   (`terraform/k8s/providers/hetzner/tests/cloud-init/worker.tftest.hcl`)
   asserts the netplan dropin is rendered and the runtime `ip addr add`
   is gone.
3. **[follow-up]** Reprovision the prod worker (`taint + tofu apply`)
   so it picks up the declarative alias path. Non-urgent — the manual
   fix is in place and the existing unit still re-applies on reboot.
4. **[follow-up]** External blackbox probe hitting
   `https://api.wordsparrow.io/health` every 60s from outside Hetzner
   (GitHub Actions cron is the cheapest viable option in this repo).
   Page on 2 consecutive failures.
5. **[follow-up]** Move the iptables NAT rules to declarative
   `nftables` config so the same fragility class doesn't apply to
   the 6443 path either. Lower priority; narrower blast radius.

## What we are NOT doing

- **No blameless retro meeting.** Solo dev project; the operator and
  the responder are the same person. The action items above ARE the
  follow-up.
- **No customer comms.** No paying users at outage time and impact
  was below detection threshold for any external party.
