package myproject;

import com.pulumi.*;
import com.pulumi.Config;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;

import com.pulumi.aws.ec2.inputs.InstanceEbsBlockDeviceArgs;
import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;
import com.pulumi.aws.ec2.inputs.SecurityGroupIngressArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZoneResult;

import com.pulumi.core.Output;
import com.pulumi.aws.s3.Bucket;

import java.util.*;
import java.util.stream.Collectors;

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

            List<Double> allowedPorts = (List<Double>) data.get("ports");
            SecurityGroup securityGroup = new SecurityGroup("securityGroup", new SecurityGroupArgs.Builder()
                            .vpcId(newvpc.id())
                            .tags(Map.of("Name:", "AMISecurityGroup"))
                            .build());

                    for (Double port : allowedPorts) {
                        SecurityGroupRule rule = new SecurityGroupRule("ingressRule-" + port, new SecurityGroupRuleArgs.Builder()
                                .type("ingress")
                                .fromPort(port.intValue())
                                .toPort(port.intValue())
                                .protocol("tcp")
                                .securityGroupId(securityGroup.id())
                                .cidrBlocks(Collections.singletonList("0.0.0.0/0"))
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
                        Double volume = (Double) data.get("volume");
                                Instance instance= new Instance("devec2",new InstanceArgs.Builder()
                                        .ami(data.get("AmiId").toString())
                                        .instanceType("t2.micro")
                                        .keyName(data.get("keyName").toString())
                                        .ebsBlockDevices(InstanceEbsBlockDeviceArgs.builder()
                                                .deviceName("/dev/xvda")
                                                .volumeType("gp2")
                                                .volumeSize(volume.intValue())
                                                .deleteOnTermination(true)
                                                .build())
                                        .vpcSecurityGroupIds(securityGroup.id().applyValue(Collections::singletonList))
                                        .subnetId(publicsubnet[0].id())
                                        .disableApiTermination(false)
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
