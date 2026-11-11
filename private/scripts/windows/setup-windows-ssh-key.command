#!/bin/bash
set -euo pipefail
KEY="$HOME/.ssh/autopilot_windows_ed25519"
mkdir -p "$HOME/.ssh/control"
chmod 700 "$HOME/.ssh" "$HOME/.ssh/control"
if [[ -e "$KEY" ]]; then
  echo 'A dedicated key already exists. This script will preserve it.'
else
  echo 'Choose a strong NONEMPTY passphrase. Enter it locally when ssh-keygen asks.'
  /usr/bin/ssh-keygen -t ed25519 -a 100 -f "$KEY" -C 'AutoPilot Mac Windows automation'
fi
# Reject an unprotected key; the approved setup requires passphrase protection.
if /usr/bin/ssh-keygen -y -P '' -f "$KEY" >/dev/null 2>&1; then
  echo 'Stopped: this key has no passphrase. Protect it with ssh-keygen -p before continuing.'
  exit 1
fi
chmod 600 "$KEY"
/usr/bin/ssh-add --apple-use-keychain "$KEY"
python3 <<'PY'
from pathlib import Path
import base64, subprocess, shutil, datetime
sshdir=Path.home()/'.ssh'
key=sshdir/'autopilot_windows_ed25519'
hostfile=sshdir/'autopilot_windows_known_hosts'
expected='SHA256:gdhYF7ebI/XYGQmc01TUolmuf8kxlcZMpvE562zhkLc'
scan=subprocess.run(['ssh-keyscan','-T','5','-t','ed25519','192.168.1.6'],capture_output=True,text=True,check=True).stdout
verified=subprocess.run(['ssh-keygen','-lf','-'],input=scan,capture_output=True,text=True,check=True).stdout
if expected not in verified:
    raise SystemExit('Host fingerprint differs from the verified laptop. Stop and recheck on Windows.')
if hostfile.exists() and hostfile.read_text().strip()!=scan.strip():
    raise SystemExit('Existing dedicated known-hosts file differs. Review it before continuing.')
hostfile.write_text(scan);hostfile.chmod(0o600)
pub=key.with_suffix('.pub').read_text().strip()
if not pub.startswith('ssh-ed25519 ') or '\n' in pub or "'" in pub:
    raise SystemExit('Unexpected public-key format.')
script=r'''$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$keyFile=Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'
$publicKey='''+"'"+pub+"'"+r'''
if(Test-Path $keyFile){
  $allowed=@('S-1-5-32-544','S-1-5-18')
  foreach($entry in (Get-Acl $keyFile).Access){
    $sid=$entry.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value
    if(-not $entry.IsInherited -and $sid -notin $allowed){throw 'Existing explicit key-file permissions need review; nothing changed'}
  }
  Copy-Item $keyFile ($keyFile+'.backup-'+(Get-Date -Format yyyyMMddHHmmss))
}
$existing=if(Test-Path $keyFile){@(Get-Content $keyFile)}else{@()}
if($existing -notcontains $publicKey){
  if((Test-Path $keyFile) -and (Get-Item $keyFile).Length -gt 0){Add-Content $keyFile '' -Encoding ascii}
  Add-Content $keyFile $publicKey -Encoding ascii
}
& icacls.exe $keyFile /inheritance:r /grant:r '*S-1-5-32-544:F' '*S-1-5-18:F'
if($LASTEXITCODE -ne 0){throw 'ACL update failed'}
icacls.exe $keyFile
'''
command='powershell.exe -NoProfile -NonInteractive -EncodedCommand '+base64.b64encode(script.encode('utf-16le')).decode()
print('Installing only the public key. If prompted, enter your Windows account password locally.',flush=True)
subprocess.run(['ssh','-o','ConnectTimeout=10','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+str(hostfile),'jagde@192.168.1.6',command],check=True)
config=sshdir/'config'
text=config.read_text() if config.exists() else ''
if 'Host autopilot-windows\n' not in text:
    if config.exists(): shutil.copy2(config,config.with_name('config.backup-'+datetime.datetime.now().strftime('%Y%m%d%H%M%S')))
    config.write_text(text+'''
Host autopilot-windows
    HostName 192.168.1.6
    User jagde
    IdentityFile ~/.ssh/autopilot_windows_ed25519
    IdentitiesOnly yes
    AddKeysToAgent yes
    UseKeychain yes
    ForwardAgent no
    StrictHostKeyChecking yes
    UserKnownHostsFile ~/.ssh/autopilot_windows_known_hosts
    ServerAliveInterval 30
    ServerAliveCountMax 3
    ControlMaster auto
    ControlPath ~/.ssh/control/%C
    ControlPersist 30m
''')
config.chmod(0o600)
subprocess.run(['ssh','-o','ControlPath=none','-o','ControlMaster=no','-o','BatchMode=yes','-o','PasswordAuthentication=no','-o','KbdInteractiveAuthentication=no','autopilot-windows','whoami'],check=True)
print('SUCCESS: fresh key-only login verified. Future command: ssh autopilot-windows whoami')
PY
