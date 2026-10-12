"""python -m unittest scripts/test_azure.py"""
import json
import unittest

from azure import app_spec, image_tag

SECRETS = {"PG_SERVER": "srv", "PG_APP_USER": "app", "PG_APP_PASSWORD": "pw-db",
           "SECUREPASS_JWT_SECRET": "pw-jwt", "SECUREPASS_MASTER_KEY": "pw-key"}


class ImageTagTest(unittest.TestCase):
    def test_commit_con_tag_de_version_usa_el_tag(self):
        self.assertEqual(image_tag("v1.2.0", "abc1234"), "v1.2.0")

    def test_commit_sin_tag_usa_el_sha(self):
        self.assertEqual(image_tag("", "abc1234"), "sha-abc1234")

    def test_tag_que_no_es_version_se_ignora(self):
        self.assertEqual(image_tag("prueba", "abc1234"), "sha-abc1234")


class AppSpecTest(unittest.TestCase):
    def setUp(self):
        self.spec = app_spec("reg/securepassb:sha-abc1234", SECRETS, "env-id", "id-id", "eastus", "x.azurecontainerapps.io")

    def test_los_secretos_no_quedan_en_variables_de_entorno(self):
        backend = self.spec["properties"]["template"]["containers"][0]
        self.assertNotIn("pw-", json.dumps(backend))
        refs = {e["name"]: e["secretRef"] for e in backend["env"] if "secretRef" in e}
        self.assertEqual(refs, {"DB_PASSWORD": "db-password", "SECUREPASS_JWT_SECRET": "jwt-secret",
                                "SECUREPASS_MASTER_KEY": "master-key"})

    def test_redis_va_como_sidecar_y_escala_maximo_a_uno(self):
        tpl = self.spec["properties"]["template"]
        self.assertEqual([c["name"] for c in tpl["containers"]], ["backend", "redis"])
        self.assertEqual(tpl["scale"]["maxReplicas"], 1)

    def test_la_imagen_se_baja_con_la_identidad_administrada(self):
        self.assertEqual(self.spec["properties"]["configuration"]["registries"][0]["identity"], "id-id")


if __name__ == "__main__":
    unittest.main()
