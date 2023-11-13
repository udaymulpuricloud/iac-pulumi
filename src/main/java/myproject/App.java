package myproject;

import com.pulumi.*;
import com.pulumi.Config;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.alb.*;
import com.pulumi.aws.alb.inputs.ListenerDefaultActionArgs;
import com.pulumi.aws.autoscaling.AutoscalingFunctions;
import com.pulumi.aws.autoscaling.Group;
import com.pulumi.aws.autoscaling.GroupArgs;
import com.pulumi.aws.autoscaling.Policy;
import com.pulumi.aws.autoscaling.PolicyArgs;
import com.pulumi.aws.autoscaling.inputs.GroupLaunchTemplateArgs;
import com.pulumi.aws.autoscaling.inputs.PolicyTargetTrackingConfigurationArgs;
import com.pulumi.aws.autoscaling.inputs.PolicyTargetTrackingConfigurationPredefinedMetricSpecificationArgs;
import com.pulumi.aws.ec2.*;

import com.pulumi.aws.ec2.inputs.*;
import com.pulumi.aws.ec2.outputs.GetAmiResult;
import com.pulumi.aws.iam.*;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;

import com.pulumi.aws.outputs.GetAvailabilityZoneResult;

import com.pulumi.aws.rds.ParameterGroup;
import com.pulumi.aws.rds.ParameterGroupArgs;
import com.pulumi.aws.rds.SubnetGroup;
import com.pulumi.aws.rds.SubnetGroupArgs;
import com.pulumi.aws.rds.inputs.ParameterGroupParameterArgs;
import com.pulumi.aws.route53.Record;
import com.pulumi.aws.route53.RecordArgs;
import com.pulumi.aws.route53.inputs.RecordAliasArgs;
import com.pulumi.core.Output;
import com.pulumi.aws.s3.Bucket;
import jdk.jshell.Snippet;

import javax.swing.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.pulumi.codegen.internal.Serialization.*;

public class App {
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
                    var config = ctx.config();
                    var data = config.requireObject("data",Map.class);

                    String vpcname = "devvpc";
                    String vpccidr = data.get("vpcCidr").toString();

                    Vpc newvpc = new Vpc(vpcname, new VpcArgs.Builder()
                            .cidrBlock(vpccidr)
                            .tags(Map.of("Name", vpcname))
                            .build());
                    InternetGateway ig = new InternetGateway("internetGateway", new InternetGatewayArgs.Builder()
                            .vpcId(newvpc.id())
                            .tags(Map.of("Name", vpcname + "-ig"))
                            .build());
                    RouteTable publicroutetable = new RouteTable("publicRouteTable", new RouteTableArgs.Builder()
                            .vpcId(newvpc.id())
                            .tags(Map.of("Name", vpcname + "-publicroutetable"))
                            .routes(RouteTableRouteArgs.builder().cidrBlock("0.0.0.0/0").gatewayId(ig.id()).build())
                            .build());
                    RouteTable privateroutetable = new RouteTable("privateRouteTable", new RouteTableArgs.Builder()
                            .vpcId(newvpc.id())
                            .tags(Map.of("Name", vpcname + "-privateroutetable"))
                            .build());
                    ParameterGroup parameterGroup = new ParameterGroup("postgrespg",new ParameterGroupArgs.Builder()
                            .family("postgres15")
                            .tags(Map.of("Name","postgrespg"))
                            .build());
                    Role cwrole = new Role("awscwrole",new RoleArgs.Builder()
                            .assumeRolePolicy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}")
                            .tags(Map.of("Name","awscwrole"))
                            .build());

                    Output<String> roleNameOutput = cwrole.name();
               Output<List<String>> listOutput = roleNameOutput.applyValue(s -> Collections.singletonList(s));

            PolicyAttachment policyAttachment =new PolicyAttachment("ec2-policy",new PolicyAttachmentArgs.Builder()
                            .policyArn("arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy")
                            .roles(listOutput)
                            .build());

                    InstanceProfile instanceProfile=new InstanceProfile("ec2awsinstanceprofile",new InstanceProfileArgs.Builder()
                         .role(cwrole.name())
                         .name("ec2awsinstanceprofile")
                            .build());

            List<Double> allowedPorts = (List<Double>) data.get("portsforec2");
            SecurityGroup application_security_group = new SecurityGroup("application_security_group", new SecurityGroupArgs.Builder()
                            .vpcId(newvpc.id())
                            .tags(Map.of("Name", "AMISecurityGroup"))
                            .build());


            List<Double> allowedPortsforrds=(List<Double>)data.get("portsforrds");
            SecurityGroup ec2_security_group =new SecurityGroup("ec2_security_group",new SecurityGroupArgs.Builder()
                    .vpcId(newvpc.id())
                    .tags(Map.of("Name","databasesecuritygroup"))
                    .build());
               for(Double ports:allowedPortsforrds){
                   SecurityGroupRule rules=new SecurityGroupRule("ingressRule-"+ports,new SecurityGroupRuleArgs.Builder()
                           .type("ingress")
                           .fromPort(ports.intValue())
                           .toPort(ports.intValue())
                           .protocol("tcp")
                           .sourceSecurityGroupId(application_security_group.id())
                           .securityGroupId(ec2_security_group.id())
                           .build());
                   SecurityGroupRule egressRulepostgres = new SecurityGroupRule("egressRulepostgres"+ports, new SecurityGroupRuleArgs.Builder()
                           .type("egress")
                           .fromPort(ports.intValue())
                           .toPort(ports.intValue())
                           .protocol("tcp")
                           .sourceSecurityGroupId(ec2_security_group.id())
                           .securityGroupId(application_security_group.id())
                           .build());
               }
               SecurityGroupRule egresshttps = new SecurityGroupRule("egresshttps", new SecurityGroupRuleArgs.Builder()
                       .type("egress")
                       .fromPort(443)
                       .toPort(443)
                       .protocol("tcp")
                       .cidrBlocks(Collections.singletonList("0.0.0.0/0"))
                       .securityGroupId(application_security_group.id())
                       .build());
               SecurityGroup loadbalancersg = new SecurityGroup("load_balancer_sg",new SecurityGroupArgs.Builder()
                       .vpcId(newvpc.id())
                       .tags(Map.of("Name","loadbalancersecuritygroup"))
                       .build());
            List<Double> allowedPortsforlb=(List<Double>)data.get("portsforlb");
            for(Double lbport : allowedPortsforlb) {
                SecurityGroupRule ingresslb = new SecurityGroupRule("lbingress "+lbport, new SecurityGroupRuleArgs.Builder()
                        .type("ingress")
                        .fromPort(lbport.intValue())
                        .toPort(lbport.intValue())
                        .protocol("tcp")
                        .securityGroupId(loadbalancersg.id())
                        .build());

            }
            for (Double port : allowedPorts) {
                SecurityGroupRule rule = new SecurityGroupRule("ingressRule-" + port, new SecurityGroupRuleArgs.Builder()
                        .type("ingress")
                        .fromPort(port.intValue())
                        .toPort(port.intValue())
                        .protocol("tcp")
                        .sourceSecurityGroupId(loadbalancersg.id())
                        .securityGroupId(application_security_group.id())
                        .build());
            }
                    var availablezones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());
                    availablezones.applyValue(avaiilable -> {
                        avaiilable.names();
                        List<String> availablelist = avaiilable.names();
                        int size = availablelist.size();
                        int numSubnets = Math.min(3, size);

                        Subnet[] publicsubnet = new Subnet[numSubnets];
                        for (int i = 0; i < numSubnets; i++) {
                            String publicSubnetCIDR = calculateSubnetCIDR(vpccidr, i, true);
                            publicsubnet[i] = new Subnet("publicsubnet" + i, new SubnetArgs.Builder()
                                    .vpcId(newvpc.id())
                                    .mapPublicIpOnLaunch(true)
                                    .availabilityZone(availablelist.get(i))
                                    .cidrBlock(publicSubnetCIDR)
                                    .tags(Map.of("Name", vpcname + "-publisubnet" + i))
                                    .build());

                            RouteTableAssociation routeTableAssocpub = new RouteTableAssociation("pubroutetableassoc" + i, new RouteTableAssociationArgs.Builder()
                                    .subnetId(publicsubnet[i].id())
                                    .routeTableId(publicroutetable.id())
                                    .build()
                            );
                        }
                        Subnet[] privatesubnet = new Subnet[numSubnets];
                        for (int i = 0; i < numSubnets; i++) {
                            String privateSubnetCIDR = calculateSubnetCIDR(vpccidr, i, false);
                            privatesubnet[i] = new Subnet("privatesubnet" + i, new SubnetArgs.Builder()
                                    .vpcId(newvpc.id())
                                    .availabilityZone(availablelist.get(i))
                                    .cidrBlock(privateSubnetCIDR)
                                    .tags(Map.of("Name", vpcname + "-privatesubnet" + i))
                                    .build());
                            RouteTableAssociation routeTableAssocpri = new RouteTableAssociation("priroutetableassoc" + i, new RouteTableAssociationArgs.Builder()
                                    .subnetId(privatesubnet[i].id())
                                    .routeTableId(privateroutetable.id())
                                    .build()
                            );
                        }

//                        Output<GetAmiResult> debianami =  Ec2Functions.getAmi(new GetAmiArgs.Builder()
//                                        .filters(new GetAmiFilterArgs.Builder()
//                                                .name("csye6225_*")
//                                                .
//                                                .build())
//                                        .build());
//                               final var debianAmi = Ec2Functions.getAmi(new GetAmiArgs.Builder()
//                                       .filters(Arrays.asList(
//                                               new GetAmiFilterArgs.Builder().name("name").values("csye6225_*").build(),
//                                               new GetAmiFilterArgs.Builder().name("description").values("*Debian*").build()))
//                                       .mostRecent(true)
//                                       .owners(data.get("owner_id").toString())
//                                       .build());

//                                Output<String> debianAmiId = debianAmi.apply();


                                List<Output<String>> subnetIds = new ArrayList<>();
                                for (Subnet subnet : privatesubnet) {
                                    subnetIds.add(subnet.id());
                                }
                                Output<List<String>> subnetIdsOutput = Output.all(subnetIds).applyValue(ids -> ids);

                                SubnetGroup privatesubnetGroup = new SubnetGroup("privatesubnetgroup", SubnetGroupArgs.builder()
                                        .subnetIds(subnetIdsOutput)
                                        .build());


                                Double dbvolume = (Double) data.get("db_volume");
                                Double portnum = (Double) data.get("port");
                               com.pulumi.aws.rds.Instance rdsDbInstance = new com.pulumi.aws.rds.Instance("csye6225", new com.pulumi.aws.rds.InstanceArgs.Builder()
                                        .engine(data.get("db_engine").toString())
                                        .instanceClass("db.t3.micro")
                                        .multiAz(false)
                                       .allocatedStorage(dbvolume.intValue())
                                       .parameterGroupName(parameterGroup.name())
                                       .dbName(data.get("db_name").toString())
                                       .username(data.get("db_username").toString())
                                       .password(data.get("db_password").toString())
                                       .vpcSecurityGroupIds(ec2_security_group.id().applyValue(Collections::singletonList))
                                       .dbSubnetGroupName(privatesubnetGroup.name())
                                       .port(portnum.intValue())
                                       .publiclyAccessible(false)
                                       .skipFinalSnapshot(true)
                                        .build());

                                String username = data.get("db_username").toString();
                                String password = data.get("db_password").toString();

                                Output<String> userDataScript =rdsDbInstance.address().applyValue(v -> String.format(
                                        "#!/bin/bash\n" +
                                                "echo 'export DB_USER=%s' >> /opt/csye6225/application.properties\n" +
                                                "echo 'export DB_PASSWORD=%s' >> /opt/csye6225/application.properties\n" +
                                                "echo 'export LOCALHOST=%s' >> /opt/csye6225/application.properties\n" +
                                                "echo 'export PORT=%s' >> /opt/csye6225/application.properties\n"+
                                                "echo 'export DB=%s' >> /opt/csye6225/application.properties\n"+
                                                "sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -c file:/opt/cloudwatch-config.json -s\n"+
                                                "sudo systemctl start amazon-cloudwatch-agent",

                                        username, password,v,portnum.intValue() ,data.get("db_name").toString()

                                ));



                                Double volume = (Double) data.get("volume");
                                Instance instance= new Instance("devec2",new InstanceArgs.Builder()
                                        .ami(data.get("AmiId").toString())
                                        .instanceType("t2.micro")
                                        .keyName(data.get("Keyname").toString())
                                        .ebsBlockDevices(InstanceEbsBlockDeviceArgs.builder()
                                                .deviceName("/dev/xvda")
                                                .volumeType("gp2")
                                                .volumeSize(volume.intValue())
                                                .deleteOnTermination(true)
                                                .build())
                                        .vpcSecurityGroupIds(application_security_group.id().applyValue(Collections::singletonList))
                                        .subnetId(publicsubnet[0].id())
                                        .disableApiTermination(false)
                                        .iamInstanceProfile(instanceProfile.name())
                                        .tags(Map.of("Name","ec2dev"))
                                        .userData(userDataScript)
                                        .build());

                                LaunchTemplate launchTemplate = new LaunchTemplate("ec2launchtemplace", new LaunchTemplateArgs.Builder()
                                        .imageId(data.get("AmiId").toString())
                                        .blockDeviceMappings(LaunchTemplateBlockDeviceMappingArgs.builder()
                                                .deviceName("/dev/xvda")
                                                .ebs(LaunchTemplateBlockDeviceMappingEbsArgs.builder()
                                                        .volumeType("gp2")
                                                        .volumeSize(volume.intValue())
                                                        .deleteOnTermination("true")
                                                        .build())
                                                .build())
                                        .iamInstanceProfile(LaunchTemplateIamInstanceProfileArgs.builder()
                                                .name(instanceProfile.name())
                                                .build())
                                        .instanceType("t2.micro")
                                        .keyName(data.get("Keyname").toString())
                                        .vpcSecurityGroupIds(application_security_group.id().applyValue(Collections::singletonList))
                                        .tags(Map.of("Name","ec2LaunchTemplate"))
                                        .userData(userDataScript)
                                        .networkInterfaces(LaunchTemplateNetworkInterfaceArgs.builder()
                                                .associatePublicIpAddress("true")
                                                .subnetId(publicsubnet[0].id())
                                                .build())
                                        .disableApiTermination(false)
                                        .build());

                                Double minsize= (Double)data.get("minec2");
                                Double maxsize= (Double)data.get("maxec2");
                                Double desiredcap=(Double)data.get("desiredcapacity");
                                TargetGroup targetGroup= new TargetGroup("targetgroup", new TargetGroupArgs.Builder()
                                        .port(80)
                                        .protocol("HTTP")
                                        .targetType("instance")
                                        .vpcId(newvpc.id())
                                        .build());
                                Group autoscale= new Group("autoscaling", new GroupArgs.Builder()
                                        .availabilityZones(availablelist)
                                        .desiredCapacity(desiredcap.intValue())
                                        .minSize(minsize.intValue())
                                        .maxSize(maxsize.intValue())
                                        .defaultCooldown(60)
                                        .launchTemplate(GroupLaunchTemplateArgs.builder()
                                                .id(launchTemplate.id())
                                                .build())
                                        .targetGroupArns(Arrays.asList(targetGroup.toString()))
                                        .build());

                                Policy upscalepolicy = new Policy("upscalepolicy", new PolicyArgs.Builder()
                                        .autoscalingGroupName(autoscale.name())
                                        .adjustmentType("ChangeInCapacity")
                                        .scalingAdjustment(1)
                                        .targetTrackingConfiguration(new PolicyTargetTrackingConfigurationArgs.Builder()
                                                .predefinedMetricSpecification(PolicyTargetTrackingConfigurationPredefinedMetricSpecificationArgs.builder()
                                                        .predefinedMetricType("ASGAverageCPUUtilization")
                                                        .build())
                                                .targetValue(5.0)
                                                .build())
                                        .build());

                                Policy downscalepolicy = new Policy("downscalepolicy", new PolicyArgs.Builder()
                                        .autoscalingGroupName(autoscale.name())
                                        .adjustmentType("ChangeInCapacity")
                                        .scalingAdjustment(-1)
                                        .targetTrackingConfiguration(new PolicyTargetTrackingConfigurationArgs.Builder()
                                                .predefinedMetricSpecification(PolicyTargetTrackingConfigurationPredefinedMetricSpecificationArgs.builder()
                                                        .predefinedMetricType("ASGAverageCPUUtilization")
                                                        .build())
                                                .targetValue(3.0)
                                                .build())
                                        .build());

                                com.pulumi.aws.alb.LoadBalancer loadBalancer= new LoadBalancer("loadbalancer",new LoadBalancerArgs.Builder()
                                        .securityGroups(loadbalancersg.id().applyValue(Collections::singletonList))
                                        .internal(false)
                                        .loadBalancerType("application")
                                        .build());


                                Listener listener = new Listener("lblistener", new ListenerArgs.Builder()
                                        .loadBalancerArn(loadBalancer.arn())
                                        .protocol("HTTP")
                                        .port(80)
                                        .defaultActions(ListenerDefaultActionArgs.builder()
                                                .type("forward")
                                                .targetGroupArn(targetGroup.arn())
                                                .build())
                                        .build());


                                Record aRecord=new Record("aRecord",new RecordArgs.Builder()
                                        .zoneId(data.get("ZoneId").toString())
                                        .name(data.get("DomainName").toString())
                                        .type("A")
                                        .ttl(60)
                                        .records(loadBalancer.dnsName().applyValue(Collections::singletonList))
//                                        .records(instance.publicIp().applyValue(Collections::singletonList))

//                                        .aliases(Collections.singletonList(RecordAliasArgs.builder()
//                                                .evaluateTargetHealth(true)
//                                                .name(data.get("DomainName").toString())
//                                                .zoneId(data.get("ZoneId").toString())
////                                                .dnsName
//                                                .build()))
                                        .build());


                                return null;

                    }
                    );
                }
        );

    }



    public static String calculateSubnetCIDR(String vpccidr, int subnetIndex, boolean isPublic) {
        String[] vpcCidrParts = vpccidr.split("\\.");
        int newThirdOctet;

        if (isPublic) {
            newThirdOctet = Integer.parseInt(vpcCidrParts[2]) + subnetIndex;
        } else {
            newThirdOctet = Integer.parseInt(vpcCidrParts[2]) + subnetIndex + 10;
        }

        return String.format("%s.%s.%s.0/24", vpcCidrParts[0], vpcCidrParts[1], newThirdOctet);
    }


}
