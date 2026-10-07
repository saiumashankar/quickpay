# Deploy QuickPay to an Azure VM

This is the simplest Azure deployment for the current project: one Ubuntu VM runs
the frontend, four Spring services, PostgreSQL, Redis, and Kafka in Docker Compose.
The frontend's Nginx container serves the production build and routes API requests
over the private Compose network. Backend, database, Redis, Kafka, Eureka, and
Mailpit ports are not published on the VM. Only HTTP port 80 is published.

This is a **demonstration deployment**, not a production payment platform. The wallet
is a simulated ledger, not a payment processor. The databases and Kafka share one VM,
there is no managed backup or high availability, and HTTP is not encrypted until you
configure a domain and TLS. Do not use real payment data or real customer data.

## 1. Create the VM and watch the $200 credit

In the Azure Portal, create a Linux Ubuntu 24.04 VM in a region near you:

- Size: `Standard_B2ms` (2 vCPU, 8 GiB RAM) is a reasonable starting point for this
  stack; a smaller VM can run out of memory while Kafka and the Java services start.
- Authentication: SSH public key.
- Inbound ports: SSH (22) restricted to **your current public IP** and HTTP (80).
  Do not open ports 8081-8083, 5432-5434, 6379, 8025, 8761, or 9092.
- Create a resource group dedicated to this experiment. Set a budget alert in
  **Cost Management + Billing → Budgets** below the credit balance (for example,
  alert at $25 and $50). Budgets alert; they do not stop resources automatically.
- Enable VM auto-shutdown while experimenting. Stopping/deallocating the VM does not
  remove charges for its disk, public IP, or other retained resources.

Prices vary by region and change over time. Check the VM, disk, and public IP prices
in the Azure portal before creating anything. To stop VM compute charges, deallocate
the VM; to stop all charges for this experiment, delete its resource group after
backing up anything you need.

Alternatively, with Azure CLI installed and `az login` completed, create the VM from
PowerShell (replace the region if needed):

```powershell
$resourceGroup = "quickpay-demo-rg"
$location = "eastus"
$vmName = "quickpay-demo-vm"
az group create --name $resourceGroup --location $location
az vm create --resource-group $resourceGroup --name $vmName `
  --location $location --image Ubuntu2404 --size Standard_B2ms `
  --admin-username azureuser --authentication-type ssh --generate-ssh-keys
az vm open-port --resource-group $resourceGroup --name $vmName `
  --port 80 --priority 1001
az vm show --show-details --resource-group $resourceGroup --name $vmName `
  --query publicIps --output tsv
```

Restrict the VM's SSH security rule to your IP in the portal before using it. The CLI
is not installed or logged in as part of this repository setup.

## 2. Install Docker and get the source

SSH to the VM using its public IP:

```powershell
ssh azureuser@<VM_PUBLIC_IP>
```

On the VM, install Docker and Compose, then clone the repository:

```bash
sudo apt-get update
sudo apt-get install -y docker.io docker-compose-v2 git
sudo systemctl enable --now docker
sudo git clone https://github.com/saiumashankar/quickpay.git /opt/quickpay
cd /opt/quickpay
```

The Azure Compose override uses Docker Compose **v2.24.4 or newer** for safe
replacement of host port and container-name settings. Check with
`docker compose version` if the distribution package is older.

If the repository is private, authenticate to GitHub securely before cloning; do not
put a personal access token in a clone URL or shell history.

## 3. Configure unique credentials and SMTP

Create the deployment-only environment file on the VM. Never commit it or paste its
values into a ticket:

```bash
cd /opt/quickpay
sudo cp .env.azure.example .env
sudo chmod 600 .env
sudo nano .env
```

Set all four application secrets to independently generated, random values. Use at
least 32 random bytes for `JWT_SECRET`; set the same `INTERNAL_API_TOKEN` for all
services (Compose does this for you). Set `MAIL_HOST`, `MAIL_USERNAME`, and
`MAIL_PASSWORD` to an SMTP relay with STARTTLS on port 587. Configure the relay to
allow the sender address `notifications@payflow.dev`, or change `MAIL_FROM` in the
`.env` file to a verified sender. Without a real SMTP relay, the production
notification service cannot deliver email. Do not use Mailpit for customer mail.

## 4. Build and start the complete stack

```bash
cd /opt/quickpay
sudo docker compose \
  -f backend/docker-compose.yml \
  -f backend/docker-compose.azure.yml \
  --env-file .env up -d --build
```

The first build downloads Java and Node dependencies and can take several minutes.
The API services wait for their databases, Redis, Kafka topics, and Eureka. Check
startup and health:

```bash
sudo docker compose \
  -f backend/docker-compose.yml \
  -f backend/docker-compose.azure.yml \
  --env-file .env ps
sudo docker compose \
  -f backend/docker-compose.yml \
  -f backend/docker-compose.azure.yml \
  --env-file .env logs --tail=100
```

Open `http://<VM_PUBLIC_IP>/`. The browser uses the same origin for the app and APIs;
Nginx reverse-proxies API paths internally and does not expose the shared Mailpit
inbox. Real payment receipts go to the configured SMTP addresses.

## 5. Verify the deployment and keep it current

Register two test accounts, sign in as the payer, top up a wallet, then make a test
payment to the second account's username/handle. Verify the balance, payment history,
and receipt email. This is a simulated wallet transfer; topping up does not charge a
card or move real money.

To deploy a later commit on the VM:

```bash
cd /opt/quickpay
sudo git pull --ff-only
sudo docker compose \
  -f backend/docker-compose.yml \
  -f backend/docker-compose.azure.yml \
  --env-file .env up -d --build
sudo docker image prune -f
```

Back up the Docker volumes before upgrades or VM deletion. Compose persists local
database and Kafka state in named volumes, but those volumes are not a backup.

For a public HTTPS URL, first point a domain at the VM, restrict SSH, and install a
TLS reverse proxy/certificate (or put Azure Application Gateway / Front Door in
front). Do not expose the service, data-store, Eureka, Mailpit, or Kafka ports to the
internet.

## Current verification and limitations

The repository CI runs the four Maven service suites, frontend checks/tests/build,
and container image builds. Run the local checks from the repository root as
described in the main README. The local stack is also exercised with `e2e-test.ps1`.
This deployment file adds the production frontend image, same-origin API routes,
SMTP configuration, and private-only backend ports; it does not turn the demo into
a payment processor or provide production data durability.
