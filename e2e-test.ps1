param(
    [string]$AuthUrl = "http://localhost:8081",
    [string]$PaymentUrl = "http://localhost:8082",
    [string]$NotificationUrl = "http://localhost:8083",
    [string]$MailpitUrl = "http://localhost:8025",
    [Parameter(Mandatory = $true)]
    [string]$Password
)

$ErrorActionPreference = "Stop"
$script:Failures = 0

function Check($label, $actual, $expected) {
    if ("$actual" -eq "$expected") {
        Write-Output ("  PASS  {0}" -f $label)
    }
    else {
        Write-Output ("  FAIL  {0}: got {1}, expected {2}" -f $label, $actual, $expected)
        $script:Failures++
    }
}

function Get-HttpStatus($request) {
    try {
        & $request | Out-Null
        return 200
    }
    catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Invoke-Payment($token, $key, $amount, $recipientHandle) {
    $headers = @{ Authorization = "Bearer $token"; "Idempotency-Key" = $key }
    $body = @{
        amount = $amount
        currency = "USD"
        recipientHandle = $recipientHandle
        description = "End-to-end verification"
    } | ConvertTo-Json

    try {
        $response = Invoke-RestMethod -Uri "$PaymentUrl/payments" -Method Post `
            -Headers $headers -ContentType "application/json" -Body $body
        return @{ status = 201; body = $response }
    }
    catch {
        $code = if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            [int]$_.Exception.Response.StatusCode
        } else {
            throw
        }
        return @{ status = $code; body = $_.ErrorDetails.Message }
    }
}

Write-Output "=== QuickPay end-to-end verification ==="

$runId = [Guid]::NewGuid().ToString("N").Substring(0, 10)
$payerHandle = "payer$runId"
$recipientHandle = "payee$runId"
$payerEmail = "payer$runId@payflow.dev"
$recipientEmail = "payee$runId@payflow.dev"

Write-Output "1. Register payer and recipient accounts"
foreach ($account in @(
    @{ username = $payerHandle; email = $payerEmail },
    @{ username = $recipientHandle; email = $recipientEmail }
)) {
    Invoke-RestMethod -Uri "$AuthUrl/api/auth/register" -Method Post `
        -ContentType "application/json" `
        -Body (@{
            username = $account.username
            email = $account.email
            password = $Password
        } | ConvertTo-Json) | Out-Null
}

$login = Invoke-RestMethod -Uri "$AuthUrl/api/auth/login" -Method Post `
    -ContentType "application/json" `
    -Body (@{ email = $payerEmail; password = $Password } | ConvertTo-Json)
$access = $login.accessToken
$refresh = $login.refreshToken
Check "login returned an access token" ($access.Length -gt 50) $true

Write-Output "2. Open and fund the payer's USD wallet"
$topUp = Invoke-RestMethod -Uri "$PaymentUrl/wallets/top-up" -Method Post `
    -Headers @{ Authorization = "Bearer $access" } `
    -ContentType "application/json" `
    -Body (@{ amount = 1000; currency = "USD" } | ConvertTo-Json)
Check "wallet currency is USD" $topUp.currency "USD"
Check "wallet balance is 1000" ([decimal]$topUp.balance) 1000

$idempotencyKey = "e2e-$runId-success"
$failedPaymentKey = "e2e-$runId-insufficient"

Write-Output "3. A successful handle payment returns 201 SUCCESS"
$successful = Invoke-Payment $access $idempotencyKey 500 $recipientHandle
Check "successful payment HTTP status" $successful.status 201
Check "successful payment state" $successful.body.status "SUCCESS"
$paymentId = $successful.body.id

Write-Output "4. An insufficient-balance payment is recorded as FAILED"
$failed = Invoke-Payment $access $failedPaymentKey 600 $recipientHandle
Check "failed payment HTTP status" $failed.status 201
Check "failed payment state" $failed.body.status "FAILED"

Write-Output "5. Identical retries replay; changed requests conflict"
$replay = Invoke-Payment $access $idempotencyKey 500 $recipientHandle
Check "retry returns the original payment" $replay.body.id $paymentId
$conflict = Invoke-Payment $access $idempotencyKey 999 $recipientHandle
Check "reusing a key with a different request returns 409" $conflict.status 409

Write-Output "6. Payment authorization and idempotency headers are enforced"
$body = @{
    amount = 1
    currency = "USD"
    recipientHandle = $recipientHandle
    description = "Authorization check"
} | ConvertTo-Json
$noAuthStatus = Get-HttpStatus {
    Invoke-RestMethod -Uri "$PaymentUrl/payments" -Method Post `
        -Headers @{ "Idempotency-Key" = "e2e-$runId-no-auth" } `
        -ContentType "application/json" -Body $body
}
Check "missing bearer token returns 401" $noAuthStatus 401
$refreshStatus = Get-HttpStatus {
    Invoke-RestMethod -Uri "$PaymentUrl/payments" -Method Post `
        -Headers @{ Authorization = "Bearer $refresh"; "Idempotency-Key" = "e2e-$runId-refresh" } `
        -ContentType "application/json" -Body $body
}
Check "refresh token is not accepted for payments" $refreshStatus 401
$noKeyStatus = Get-HttpStatus {
    Invoke-RestMethod -Uri "$PaymentUrl/payments" -Method Post `
        -Headers @{ Authorization = "Bearer $access" } `
        -ContentType "application/json" -Body $body
}
Check "missing Idempotency-Key returns 400" $noKeyStatus 400

Write-Output "7. Payment history is scoped to the signed-in user"
$payments = Invoke-RestMethod -Uri "$PaymentUrl/payments/me" `
    -Headers @{ Authorization = "Bearer $access" }
Check "history contains both test payments" (@($payments).Count -ge 2) $true
$payerOnly = $true
foreach ($payment in $payments) {
    if ($payment.userId -ne $login.user.id) { $payerOnly = $false }
}
Check "every returned payment belongs to the payer" $payerOnly $true

Write-Output "8. Notifications require authentication and email is delivered"
$notificationStatus = Get-HttpStatus {
    Invoke-RestMethod -Uri "$NotificationUrl/notify/test?email=$payerEmail&amount=1"
}
Check "unauthenticated notification test returns 401" $notificationStatus 401

Start-Sleep -Seconds 8
$mail = Invoke-RestMethod -Uri "$MailpitUrl/api/v1/messages" -TimeoutSec 20
$subjects = @($mail.messages | ForEach-Object { $_.Subject })
Check "success email was delivered" ($subjects -contains "Payment successful") $true
Check "failure email was delivered" ($subjects -contains "Payment failed") $true

if ($script:Failures -eq 0) {
    Write-Output "=== all checks passed ==="
    exit 0
}

Write-Output ("=== {0} check(s) failed ===" -f $script:Failures)
exit 1
