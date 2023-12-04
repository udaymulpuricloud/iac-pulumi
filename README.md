# iac-pulumi
Install and set up the AWS CLI using the following commands:
aws configure --profile dev
aws configure --profile demo

To deploy the project, use the following command:
pulumi up

To change the stack:
pulumi select stack dev/demo

To create a new stack:
pulumi stack init example

To destroy the project:
pulumi destroy

To refresh the infra :
pulumi refresh


Run the below command in the AWS CLI to create a certificate for the domain name in the folder terminal ::

aws iam upload-server-certificate --server-certificate-name demo.udaykiranreddy.me 
--certificate-body file://certificate.crt --private-key file://private.key --certificate-chain file://ca_bundle.crt 
--profile demo --region us-east-1

