#!/usr/bin/env python3
"""Operacion manual de securepassb en Azure (build, push, deploy, start/stop, logs).

Reemplaza a build.py de la catedra. Usa la misma regla de tags que el CI
(.github/workflows), asi una imagen subida a mano y una del pipeline se
llaman igual:
  - commit con tag git vX.Y.Z  -> securepassb:vX.Y.Z
  - cualquier otro commit      -> securepassb:sha-<7 caracteres>

Requiere docker y az logueado (`az account show`). Uso:
  python scripts/azure.py tag | build | push | tags | deploy [--tag T]
  python scripts/azure.py status | revisions | logs [-f] | url
  python scripts/azure.py start | stop | delete | monitor

Los nombres de recursos salen de variables de entorno con los mismos nombres
que las GitHub Variables (defaults abajo). Los secretos de runtime solo se
leen al crear la app, de %USERPROFILE%\\.securepassb\\azure-secrets.env, y
nunca se imprimen.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

CFG = {
    "AZURE_RESOURCE_GROUP": "universidad",
    "ACR_NAME": "acringenieriaum",
    "IMAGE_NAME": "securepassb",
    "CONTAINER_APP_NAME": "securepassb",
    "CONTAINER_APP_ENV": "managedEnvironment-universidad-8a60",
    "ACA_IDENTITY": "id-securepassb",
}
CFG.update({k: os.environ[k] for k in CFG if os.environ.get(k)})
SECRETS_FILE = Path.home() / ".securepassb" / "azure-secrets.env"
RG, APP = CFG["AZURE_RESOURCE_GROUP"], CFG["CONTAINER_APP_NAME"]
REGISTRY = f"{CFG['ACR_NAME']}.azurecr.io"


def run(*cmd, capture=False, check=True):
    exe = shutil.which(cmd[0])  # en Windows az es az.cmd
    if exe is None:
        sys.exit(f"[error] no se encontro '{cmd[0]}' en el PATH")
    if not capture:
        print("[cmd]", " ".join(cmd))
    res = subprocess.run([exe, *cmd[1:]], capture_output=capture, text=True)
    if check and res.returncode != 0:
        sys.exit(f"[error] fallo: {' '.join(cmd[:4])} ... (exit {res.returncode})\n{(res.stderr or '').strip()}")
    return res.stdout.strip() if capture else res.returncode


def az_json(*args):
    out = run("az", *args, "-o", "json", capture=True)
    return json.loads(out) if out else None


def git(*args):
    return run("git", *args, capture=True, check=False)


def image_tag(exact_tag, short_sha):
    """Misma regla que el CI: tag git vX.Y.Z si existe, si no sha-<7>."""
    if exact_tag and exact_tag.startswith("v"):
        return exact_tag
    return f"sha-{short_sha}"


def current_tag():
    return image_tag(git("describe", "--exact-match", "--tags", "HEAD"), git("rev-parse", "--short=7", "HEAD"))


def image_ref(tag):
    return f"{REGISTRY}/{CFG['IMAGE_NAME']}:{tag}"


def read_secrets():
    if not SECRETS_FILE.exists():
        sys.exit(f"[error] falta {SECRETS_FILE} (ver docs/azure.md)")
    pairs = (l.split("=", 1) for l in SECRETS_FILE.read_text().splitlines() if "=" in l and not l.startswith("#"))
    return {k.strip(): v.strip() for k, v in pairs}


# Application Insights: el agente esta en la imagen y se activa con
# JDK_JAVA_OPTIONS (JAVA_TOOL_OPTIONS de la imagen sigue con las opciones de memoria).
APPINSIGHTS_ENV = [
    {"name": "JDK_JAVA_OPTIONS", "value": "-javaagent:/app/applicationinsights-agent.jar"},
    {"name": "APPLICATIONINSIGHTS_CONNECTION_STRING", "secretRef": "appinsights-connection"},
    {"name": "APPLICATIONINSIGHTS_ROLE_NAME", "value": "securepassb"},
]


def app_spec(image, s, env_id, identity_id, location, fqdn_base):
    """Spec de la Container App: backend + Redis sidecar. Los secretos van en
    configuration.secrets y el backend los ve por secretRef."""
    def probe(kind, path, period, failures):
        return {"type": kind, "httpGet": {"path": path, "port": 8080}, "periodSeconds": period, "failureThreshold": failures}

    env = [
        {"name": "SPRING_PROFILES_ACTIVE", "value": "prod"},
        {"name": "DB_URL", "value": f"jdbc:postgresql://{s['PG_SERVER']}.postgres.database.azure.com:5432/securepass?sslmode=require"},
        {"name": "DB_USER", "value": s["PG_APP_USER"]},
        {"name": "DB_PASSWORD", "secretRef": "db-password"},
        {"name": "REDIS_HOST", "value": "localhost"},
        {"name": "SECUREPASS_JWT_SECRET", "secretRef": "jwt-secret"},
        {"name": "SECUREPASS_MASTER_KEY", "secretRef": "master-key"},
        {"name": "SECUREPASS_ACTIVATION_BASE_URL", "value": f"https://{APP}.{fqdn_base}"},
    ]
    secrets = [
        {"name": "db-password", "value": s["PG_APP_PASSWORD"]},
        {"name": "jwt-secret", "value": s["SECUREPASS_JWT_SECRET"]},
        {"name": "master-key", "value": s["SECUREPASS_MASTER_KEY"]},
    ]
    if s.get("APPINSIGHTS_CONNECTION_STRING"):
        env += APPINSIGHTS_ENV
        secrets.append({"name": "appinsights-connection", "value": s["APPINSIGHTS_CONNECTION_STRING"]})
    return {
        "location": location,
        "identity": {"type": "UserAssigned", "userAssignedIdentities": {identity_id: {}}},
        "properties": {
            "environmentId": env_id,
            "configuration": {
                "activeRevisionsMode": "Single",
                "ingress": {"external": True, "targetPort": 8080, "transport": "auto", "allowInsecure": False},
                "registries": [{"server": REGISTRY, "identity": identity_id}],
                "secrets": secrets,
            },
            "template": {
                "containers": [
                    {
                        "name": "backend", "image": image, "env": env,
                        "resources": {"cpu": 0.75, "memory": "1.5Gi"},
                        "probes": [
                            # Spring tarda en arrancar con 0,75 vCPU: hasta 5 min antes de darlo por muerto.
                            probe("Startup", "/actuator/health/liveness", 10, 30),
                            probe("Liveness", "/actuator/health/liveness", 30, 3),
                            probe("Readiness", "/actuator/health/readiness", 10, 3),
                        ],
                    },
                    {
                        # Sidecar: solo escucha en localhost, sin disco (sesiones efimeras).
                        "name": "redis", "image": "docker.io/library/redis:7-alpine",
                        "command": ["redis-server"], "args": ["--save", "", "--appendonly", "no", "--bind", "127.0.0.1"],
                        "resources": {"cpu": 0.25, "memory": "0.5Gi"},
                    },
                ],
                # ponytail: max 1 replica porque Redis es por replica; Redis aparte si hay que escalar.
                "scale": {"minReplicas": 0, "maxReplicas": 1},
            },
        },
    }


def app_exists():
    show = [shutil.which("az"), "containerapp", "show", "-g", RG, "-n", APP, "-o", "none"]
    return subprocess.run(show, capture_output=True).returncode == 0


def require_tag_in_acr(tag):
    tags = az_json("acr", "repository", "show-tags", "-n", CFG["ACR_NAME"], "--repository", CFG["IMAGE_NAME"]) or []
    if tag not in tags:
        sys.exit(f"[error] {image_ref(tag)} no esta en el ACR. Subila con: python scripts/azure.py push --tag {tag}")


def cmd_build(a):
    tag = a.tag or current_tag()
    run("docker", "build", "--platform", "linux/amd64",
        "--build-arg", f"VERSION={tag}",
        "--build-arg", f"REVISION={git('rev-parse', 'HEAD')}",
        "--build-arg", f"CREATED={datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}",
        "-t", image_ref(tag), ".")
    print(f"[ok] imagen local {image_ref(tag)}")


def cmd_push(a):
    tag = a.tag or current_tag()
    run("az", "acr", "login", "-n", CFG["ACR_NAME"])
    run("docker", "push", image_ref(tag))
    require_tag_in_acr(tag)
    print(f"[ok] {image_ref(tag)} esta en el ACR")


def cmd_tags(_):
    rows = az_json("acr", "repository", "show-tags", "-n", CFG["ACR_NAME"], "--repository", CFG["IMAGE_NAME"],
                   "--detail", "--orderby", "time_desc") or []
    for r in rows:
        print(f"{r['name']:24} {r['lastUpdateTime'][:19]}  {r['digest'][:19]}")


def cmd_deploy(a):
    tag = a.tag or current_tag()
    require_tag_in_acr(tag)
    image = image_ref(tag)
    if app_exists():
        run("az", "containerapp", "update", "-g", RG, "-n", APP, "--container-name", "backend", "--image", image, "-o", "none")
    else:
        env = az_json("containerapp", "env", "show", "-g", RG, "-n", CFG["CONTAINER_APP_ENV"])
        identity_id = az_json("identity", "show", "-g", RG, "-n", CFG["ACA_IDENTITY"])["id"]
        spec = app_spec(image, read_secrets(), env["id"], identity_id, env["location"], env["properties"]["defaultDomain"])
        spec_file = SECRETS_FILE.parent / "aca-create.yaml"  # carpeta con permisos solo del usuario
        try:
            spec_file.write_text(json.dumps(spec))  # JSON es YAML valido
            run("az", "containerapp", "create", "-g", RG, "-n", APP, "--yaml", str(spec_file), "-o", "none")
        finally:
            spec_file.unlink(missing_ok=True)
    print(f"[ok] desplegado {image}")
    cmd_status(a)


def cmd_monitor(_):
    """Activa Application Insights en la app existente (crea una revision nueva)."""
    conn = read_secrets().get("APPINSIGHTS_CONNECTION_STRING")
    if not conn:
        sys.exit(f"[error] falta APPINSIGHTS_CONNECTION_STRING en {SECRETS_FILE} (ver docs/monitoreo.md)")
    print("[cmd] az containerapp secret set ... appinsights-connection=<oculto>")
    run("az", "containerapp", "secret", "set", "-g", RG, "-n", APP, "--secrets", f"appinsights-connection={conn}",
        "-o", "none", capture=True)
    env_args = [f"{e['name']}=" + (f"secretref:{e['secretRef']}" if "secretRef" in e else e["value"]) for e in APPINSIGHTS_ENV]
    run("az", "containerapp", "update", "-g", RG, "-n", APP, "--container-name", "backend", "--set-env-vars", *env_args, "-o", "none")
    print("[ok] Application Insights activado")
    cmd_status(_)


def cmd_status(_):
    app = az_json("containerapp", "show", "-g", RG, "-n", APP)
    p = app["properties"]
    print(json.dumps({
        "estado": p.get("runningStatus"),
        "url": f"https://{p['configuration']['ingress']['fqdn']}",
        "imagen": p["template"]["containers"][0]["image"],
        "revision": p.get("latestRevisionName"),
        "replicas_min_max": [p["template"]["scale"].get("minReplicas"), p["template"]["scale"].get("maxReplicas")],
    }, indent=2))


def cmd_revisions(_):
    for r in az_json("containerapp", "revision", "list", "-g", RG, "-n", APP, "--all") or []:
        p = r["properties"]
        print(f"{r['name']:32} activa={p['active']!s:5} replicas={p.get('replicas', 0)} "
              f"{p['createdTime'][:19]}  {p['template']['containers'][0]['image'].split('/')[-1]}")


def cmd_logs(a):
    args = ["az", "containerapp", "logs", "show", "-g", RG, "-n", APP, "--container", a.container, "--tail", "100"]
    run(*args, *(["--follow"] if a.follow else []))


def cmd_url(_):
    print("https://" + az_json("containerapp", "show", "-g", RG, "-n", APP)["properties"]["configuration"]["ingress"]["fqdn"])


def lifecycle(action):
    app_id = az_json("containerapp", "show", "-g", RG, "-n", APP)["id"]
    print(f"[cmd] az rest --method post --url .../containerApps/{APP}/{action}")
    run("az", "rest", "--method", "post", "--url", f"https://management.azure.com{app_id}/{action}?api-version=2024-03-01",
        "-o", "none", capture=True)
    print(f"[ok] {action} enviado (python scripts/azure.py status para ver el estado)")


def cmd_delete(_):
    print(f"Esto borra la Container App '{APP}' (no borra imagenes ni la base).")
    if input(f"Escribi '{APP}' para confirmar: ").strip() != APP:
        sys.exit("[cancelado]")
    run("az", "containerapp", "delete", "-g", RG, "-n", APP, "--yes")


def main():
    parser = argparse.ArgumentParser(description="Operacion de securepassb en Azure")
    sub = parser.add_subparsers(dest="cmd", required=True)
    for name in ("build", "push", "deploy"):
        sub.add_parser(name).add_argument("--tag", help="por defecto: el del commit actual (vX.Y.Z o sha-xxxxxxx)")
    logs = sub.add_parser("logs")
    logs.add_argument("-f", "--follow", action="store_true")
    logs.add_argument("--container", default="backend", choices=["backend", "redis"])
    for name in ("tag", "tags", "status", "revisions", "url", "start", "stop", "delete", "monitor"):
        sub.add_parser(name)
    a = parser.parse_args()
    handlers = {
        "tag": lambda _: print(current_tag()), "build": cmd_build, "push": cmd_push, "tags": cmd_tags,
        "deploy": cmd_deploy, "status": cmd_status, "revisions": cmd_revisions, "logs": cmd_logs,
        "url": cmd_url, "start": lambda _: lifecycle("start"), "stop": lambda _: lifecycle("stop"),
        "delete": cmd_delete, "monitor": cmd_monitor,
    }
    os.environ.setdefault("MSYS_NO_PATHCONV", "1")  # Git Bash no debe tocar rutas /subscriptions/...
    handlers[a.cmd](a)


if __name__ == "__main__":
    main()
