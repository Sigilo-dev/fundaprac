"""Auditoria simple de rutas externas del caso CampusSpace.
Lee rutas.txt y clasifica los dominios para apoyar el diagrama C4.
No realiza llamadas reales a Internet.
"""
from pathlib import Path
from urllib.parse import urlparse
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]
ROUTES_FILE = ROOT / "rutas.txt"

routes = [line.strip() for line in ROUTES_FILE.read_text(encoding="utf-8").splitlines() if line.strip()]
domains = [urlparse(route).netloc for route in routes]

print("Total de sites externos:", len(routes))
print("Dominios detectados:")
for domain, count in Counter(domains).items():
    print(f"- {domain}: {count}")
print("\nEn el diagrama C4, cada dominio debe modelarse como sistema externo o agruparse justificadamente.")
