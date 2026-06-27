"""Cliente de prueba para PC02 CampusSpace.
Ejecutar luego de levantar source-service y composer-service.
No requiere librerias externas.
"""
import json
import os
import sys
import urllib.request

SOURCE_URL = os.getenv("SOURCE_URL", "http://localhost:4570")
COMPOSER_URL = os.getenv("COMPOSER_URL", "http://localhost:4571")


def get_json(url: str):
    with urllib.request.urlopen(url, timeout=8) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    checks = [
        ("Source health", f"{SOURCE_URL}/health"),
        ("Source reservas", f"{SOURCE_URL}/reservas?limit=5"),
        ("Composer health", f"{COMPOSER_URL}/health"),
        ("Composer dashboard", f"{COMPOSER_URL}/dashboard"),
    ]
    failed = False
    for name, url in checks:
        try:
            data = get_json(url)
            print(f"\n=== {name} ===")
            print(json.dumps(data, indent=2, ensure_ascii=False)[:1500])
        except Exception as exc:
            failed = True
            print(f"ERROR en {name}: {exc}", file=sys.stderr)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
