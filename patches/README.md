# Patches to upstream open_health

Applied in order by the Dockerfile's build stage, right after checking out
`OPEN_HEALTH_REV`. A patch that no longer applies fails the build.

| Patch | Why |
|-------|-----|
| `dashboard-trim.patch` | Removes dashboard controls whose routes `bix-infra/nginx/health.conf` 404s on purpose: the Live heart rate panel (`/api/live-hr`), the DNA and Blood links (`/dna`, `/blood`), the ring auth key Export/Import buttons (`/api/ring-key`) and the tap-to-toggle on capabilities (`/api/feature`; the on/off states still show). Functions they called are left in place to keep the diff small. |

To change one: check out the pin, `git apply` the patches, edit, then
`git diff > patches/<name>.patch` (paths relative to the open_health root).
