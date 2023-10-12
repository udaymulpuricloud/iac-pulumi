package myproject;

import com.pulumi.*;
import com.pulumi.Config;
import com.pulumi.Pulumi;
import com.pulumi.aws.AwsFunctions;
import com.pulumi.aws.ec2.*;

import com.pulumi.aws.ec2.inputs.RouteTableRouteArgs;
import com.pulumi.aws.inputs.GetAvailabilityZonesArgs;
import com.pulumi.aws.outputs.GetAvailabilityZoneResult;

import com.pulumi.core.Output;
import com.pulumi.aws.s3.Bucket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class App {
    public static void main(String[] args) {
        Pulumi.run(ctx -> {
            var config =  ctx.config();
            String vpcname="devvpc";
            String vpccidr= config.require("vpcCidr");
            String region=config.require("aws-region");


            Vpc newvpc= new Vpc(vpcname,new VpcArgs.Builder()
                    .cidrBlock(vpccidr)
                    .tags(Map.of("Name",vpcname))
                    .build());
            InternetGateway ig= new InternetGateway("internetGateway",new InternetGatewayArgs.Builder()
                    .vpcId(newvpc.id())
                    .tags(Map.of("Name",vpcname+"-ig"))
                    .build());
            RouteTable publicroutetable=new RouteTable("publicRouteTable",new RouteTableArgs.Builder()
                    .vpcId(newvpc.id())
                    .tags(Map.of("Name",vpcname+"-publicroutetable"))
                    .routes(RouteTableRouteArgs.builder().cidrBlock("0.0.0.0/0").gatewayId(ig.id()).build())
                    .build());
            RouteTable privateroutetable=new RouteTable("privateRouteTable",new RouteTableArgs.Builder()
                    .vpcId(newvpc.id())
                    .tags(Map.of("Name",vpcname+"-privateroutetable"))
                    .build());

            var availablezones = AwsFunctions.getAvailabilityZones(GetAvailabilityZonesArgs.builder().state("available").build());
            availablezones.applyValue(avaiilable -> {
                avaiilable.names();
                List<String> availablelist = avaiilable.names();
                int size=availablelist.size();
                Subnet[] publicsubnet=new Subnet[3];
                for(int i=0;i<3;i++){
                    String publicSubnetCIDR = "10.0." + (i * 10) + ".0/24";
                    publicsubnet[i]=new Subnet("publicsubnet"+i,new SubnetArgs.Builder()
                            .vpcId(newvpc.id())
                            .mapPublicIpOnLaunch(true)
                            .availabilityZone(availablelist.get(i%size))
                            .cidrBlock(publicSubnetCIDR)
                            .tags(Map.of("Name",vpcname+"-publisubnet"+i))
                            .build());

                    RouteTableAssociation routeTableAssocpub=new RouteTableAssociation("pubroutetableassoc"+i,new RouteTableAssociationArgs.Builder()
                            .subnetId(publicsubnet[i].id())
                            .routeTableId(publicroutetable.id())
                            .build()
                           );
                }
                Subnet[] privatesubnet=new Subnet[3];
                for(int i=0;i<3;i++){
                    String privateSubnetCIDR = "10.0." + (i * 10+5) + ".0/24";
                    privatesubnet[i]=new Subnet("privatesubnet"+i,new SubnetArgs.Builder()
                            .vpcId(newvpc.id())
                            .availabilityZone(availablelist.get(i%size))
                            .cidrBlock(privateSubnetCIDR)
                            .tags(Map.of("Name",vpcname+"-privatesubnet"+i))
                            .build());
                    RouteTableAssociation routeTableAssocpri=new RouteTableAssociation("priroutetableassoc"+i,new RouteTableAssociationArgs.Builder()
                            .subnetId(privatesubnet[i].id())
                            .routeTableId(privateroutetable.id())
                            .build()
                    );
                }
                return null;
            });




        });

    }
}
