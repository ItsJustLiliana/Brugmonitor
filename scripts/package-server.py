"""Create a portable server zip with an explicit file list; never include keys."""
from pathlib import Path
import zipfile

base = Path(__file__).resolve().parents[1]
names = ["app.py", "cloud.py", "server.py", "requirements.txt", "index.html", ".env.example",
         "SERVER-SETUP.md", "firebase/firestore.rules", "firebase.json",
         "deploy/brugmonitor.service", "deploy/install-user-service.sh", "deploy/update.sh", "deploy/auto-update.sh", "AUTOMATION.md",
         "scripts/send-test-push.py", "tests/test_cloud.py", "tests/test_app.py", "tests/test_deploy.py"]
target = base / "dist" / "Brugmonitor-server.zip"
target.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for name in names:
        info = zipfile.ZipInfo(name)
        info.create_system = 3
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o100755 if name.endswith(".sh") else 0o100644) << 16
        archive.writestr(info, (base / name).read_bytes())
print(target)
