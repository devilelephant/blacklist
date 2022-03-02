package com.devilelephant;

import static java.util.Collections.singletonList;
import static software.amazon.awscdk.core.BundlingOutput.ARCHIVED;
import static software.amazon.awscdk.services.lambda.FileSystem.fromEfsAccessPoint;

import java.util.List;
import software.amazon.awscdk.core.BundlingOptions;
import software.amazon.awscdk.core.CfnOutput;
import software.amazon.awscdk.core.CfnOutputProps;
import software.amazon.awscdk.core.Construct;
import software.amazon.awscdk.core.DockerVolume;
import software.amazon.awscdk.core.Duration;
import software.amazon.awscdk.core.RemovalPolicy;
import software.amazon.awscdk.core.Stack;
import software.amazon.awscdk.core.StackProps;
import software.amazon.awscdk.services.apigatewayv2.AddRoutesOptions;
import software.amazon.awscdk.services.apigatewayv2.HttpApi;
import software.amazon.awscdk.services.apigatewayv2.HttpApiProps;
import software.amazon.awscdk.services.apigatewayv2.HttpMethod;
import software.amazon.awscdk.services.apigatewayv2.PayloadFormatVersion;
import software.amazon.awscdk.services.apigatewayv2.integrations.LambdaProxyIntegration;
import software.amazon.awscdk.services.apigatewayv2.integrations.LambdaProxyIntegrationProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.efs.AccessPoint;
import software.amazon.awscdk.services.efs.AccessPointOptions;
import software.amazon.awscdk.services.efs.Acl;
import software.amazon.awscdk.services.efs.FileSystem;
import software.amazon.awscdk.services.efs.PosixUser;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.assets.AssetOptions;

public class CdkStack extends Stack {

  // per docs must start with "/mnt"
  public static final String MOUNT_PATH = "/mnt/firehol";

  public CdkStack(final Construct parent, final String id) {
    this(parent, id, null);
  }

  public CdkStack(final Construct parent, final String id, final StackProps props) {
    super(parent, id, props);

    // EFS needs to be setup in a VPC
    Vpc vpc = Vpc.Builder.create(this, "Vpc")
        .maxAzs(2)
        .build();

    // Create a file system in EFS to store information
    FileSystem fileSystem = FileSystem.Builder.create(this, "FileSystem")
        .vpc(vpc)
        .removalPolicy(RemovalPolicy.DESTROY)
        .build();

    // Create am access point to EFS
    AccessPoint accessPoint = fileSystem.addAccessPoint("AccessPoint",
        AccessPointOptions.builder()
            .createAcl(
                Acl
                    .builder()
                    .ownerGid("1001").ownerUid("1001").permissions("750")
                    .build())
            .path("/export/lambda")
            .posixUser(
                PosixUser
                    .builder()
                    .gid("1001").uid("1001")
                    .build())
            .build());

    // Shared java/maven lambda building options
    var builderOptions = BundlingOptions.builder()
        .image(Runtime.JAVA_11.getBundlingImage())
        .volumes(singletonList(
            // Mount local .m2 repo to avoid download all the dependencies again inside the container
            DockerVolume.builder()
                .hostPath(System.getProperty("user.home") + "/.m2/")
                .containerPath("/root/.m2/")
                .build()
        ))
        .user("root")
        .outputType(ARCHIVED);

    // IP Check Lambda
    var ipCheckFunction = new Function(this, "IpCheck", FunctionProps.builder()
        .runtime(Runtime.JAVA_11)
        .code(Code.fromAsset("../software/", AssetOptions.builder()
            .bundling(builderOptions
                .command(List.of(
                    "/bin/sh",
                    "-c",
                    "cd ipcheck && mvn clean install && cp /asset-input/ipcheck/target/ipcheck_shaded.jar /asset-output/"
                ))
                .build())
            .build()))
        .handler("com.devilelephant.ipcheck.IpCheckFn")
        .memorySize(512)
        .timeout(Duration.seconds(30))
        .vpc(vpc)
        .filesystem(fromEfsAccessPoint(accessPoint, MOUNT_PATH))
        .logRetention(RetentionDays.ONE_WEEK)
        .tracing(Tracing.ACTIVE)
        .build());

    // API Gateway Definition and IP Check Routing
    var httpApi = new HttpApi(this, "blocklist-api", HttpApiProps.builder()
        .apiName("blocklist-api")
        .build());

    httpApi.addRoutes(AddRoutesOptions.builder()
        .path("/blocklist")
        .methods(singletonList(HttpMethod.GET))
        .integration(new LambdaProxyIntegration(LambdaProxyIntegrationProps.builder()
            .handler(ipCheckFunction)
            .payloadFormatVersion(PayloadFormatVersion.VERSION_2_0)
            .build()))
        .build());

    // Firehol Updater Lambda
    var fireholUpdaterFn = new Function(this, "FireholUpdater", FunctionProps.builder()
        .runtime(Runtime.JAVA_11)
        .code(Code.fromAsset("../software/", AssetOptions.builder()
            .bundling(builderOptions
                .command(List.of(
                    "/bin/sh",
                    "-c",
                    "cd fireholupdater && mvn clean install && cp /asset-input/fireholupdater/target/fireholupdater_shaded.jar /asset-output/"
                ))
                .build())
            .build()))
        .handler("com.devilelephant.fireholupdater.FireholUpdaterFn")
        .memorySize(2048)
        .timeout(Duration.minutes(5))
        .vpc(vpc)
        .filesystem(software.amazon.awscdk.services.lambda.FileSystem.fromEfsAccessPoint(accessPoint, MOUNT_PATH))
        .logRetention(RetentionDays.ONE_WEEK)
        .tracing(Tracing.ACTIVE)
        .build());

    // Output the URL to API Gateway Endpoint
    new CfnOutput(this, "HttApi", CfnOutputProps.builder()
        .description("Url for Http Api")
        .value(httpApi.getApiEndpoint())
        .build());

    // EventBridge Rule Definition and Targeting Firehol Update Lambda
    Rule ruleScheduled = Rule.Builder.create(this, "triggerFireholUpdater")
        .schedule(Schedule.rate(Duration.hours(1)))
        .build();

    // Set the target of our EventBridge rule to our Lambda function
    ruleScheduled.addTarget(new LambdaFunction(fireholUpdaterFn));
  }
}
