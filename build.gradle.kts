
 repositories {
     mavenLocal()
     mavenCentral()
 }

plugins {
    java
}

 dependencies {
     compileOnly("software.amazon.smithy.typescript:smithy-aws-typescript-codegen:0.21.1")
     compileOnly("software.amazon.smithy.typescript:smithy-typescript-codegen:0.21.1")
 }