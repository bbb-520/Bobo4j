# Operations monitoring

`metrics.py` exposes only fixed aggregate SQL results, Docker process/memory metrics and backup status. It does not accept SQL, expose user data or serialize dependency errors. Use a dedicated `bobo_observer` account with SELECT only on the seven named tables. Keep its JSON configuration root-owned mode0600 and bind to the private Docker bridge gateway, never a public address.

Install `bobo-metrics.service`; configure Prometheus's `host.docker.internal` extra host to the **actual gateway of the application network**. Docker's default `host-gateway` can resolve to a different bridge and fail to reach a listener bound to the application bridge. `/health` reports a fresh collection loop; source failures remain visible in `/metrics` and have a separate alert.

The Prometheus files are under `infra/prometheus`. Mount both alert rule files and configure the Alertmanager target. `alertmanager.yml.example` uses verified TLS with implicit SMTP on465. Replace sender/username/recipient privately; mount the password file root-readable, not in the repository. The deployed Alertmanager0.33.1 was validated using matching `amtool`, pinned by digest. Its host port is loopback-only. A successful email notification counter with zero failures proves SMTP acceptance; verify delivery in the recipient inbox separately.

Alert coverage includes source scrape/collection failures, stopped/restarting containers, memory over90%, wallet lock over660seconds, image queue over300seconds, unknown image outcomes, expired/manual payments/refunds, stale/failed backups and notification failures. Estimated unknown image exposure is a risk estimate, not confirmed spending. Thresholds are starting policies; tune against measured service behavior. These metrics do not establish a capacity benchmark or high availability.

```sh
python3 -m unittest discover -s ops/monitoring -p 'test_*.py'
# On the private host, access monitoring via SSH forwarding:
ssh -i ~/.ssh/id_ed25519 -L 19090:127.0.0.1:19090 -L 19093:127.0.0.1:19093 root@YOUR_HOST
```
