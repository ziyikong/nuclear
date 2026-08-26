# server2 传奇服 自动重启包装
$env:JAVA_HOME = "D:\Java\jdk-17.0.19+10"
while ($true) {
    & "D:\Java\jdk-21.0.7\bin\java.exe" -jar "D:\AIs\nuclear\server-release.jar"
    Write-Host "server2 退出,5秒后重启..."
    Start-Sleep -Seconds 5
}