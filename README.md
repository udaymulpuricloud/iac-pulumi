# iac-pulumi
Initiate a new pulumi project with pulumi new
Logged in locally
set the region ,cidr in the stack.yaml files
after updating the code , need to hit pulumi up , then give the password and set the infrastructure 
Creating the required infrastructure such as Vpc 
adding subnets as required under the vpc
creating rds with security group and ingress rules
then need to create the ec2 with newly created vpc and a new security group with required ports
need to hit pulumi destroy to destroy all the infrastructure 

