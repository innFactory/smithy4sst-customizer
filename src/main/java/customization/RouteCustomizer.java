package customization;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.pattern.SmithyPattern;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.typescript.codegen.CodegenUtils;
import software.amazon.smithy.typescript.codegen.TypeScriptCodegenContext;
import software.amazon.smithy.typescript.codegen.TypeScriptSettings;
import software.amazon.smithy.typescript.codegen.TypeScriptWriter;
import software.amazon.smithy.typescript.codegen.integration.TypeScriptIntegration;
import software.amazon.smithy.utils.SmithyInternalApi;

import java.io.*;
import java.util.Map;
import java.util.stream.Collectors;

@SmithyInternalApi
public final class RouteCustomizer implements TypeScriptIntegration {

    private static class OperationReference {
        String path;
        String handlerPath;
        String functionId;
        String operationName;
        String parentResource;

        OperationReference(String path, String handlerPath, String functionId, String operationName, String parentResource) {
            this.path = path;
            this.handlerPath = handlerPath;
            this.functionId = functionId;
            this.operationName = operationName;
            this.parentResource = parentResource;
        }

        public String getPath() {
            return path;
        }

        public String getHandlerPath() {
            return handlerPath;
        }

        public String getFunctionId() {
            return functionId;
        }

        public String getOperationName() {
            return operationName;
        }

        public String getOperationNameFirstCharUppercase() {
            return Character.toUpperCase(operationName.charAt(0)) + operationName.substring(1);
        }

        public String getParentResource() {
            return parentResource;
        }

        public String getParentResourceFirstCharUppercase() {
            return Character.toUpperCase(parentResource.charAt(0)) + parentResource.substring(1);
        }
    }

    String camelToKebabCase(String str) {
        return str.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    void writeOperation(String resourceName, OperationShape operation, List<OperationReference> operationUnion, TypeScriptWriter writer) {
        var operationName = operation.toShapeId().getName();
        var handlerPath = "services/functions/" + resourceName.toLowerCase() + "/application/handler/" + operationName.toLowerCase() + ".handler";
        var functionId = camelToKebabCase(operationName);

        var trait = operation.getTrait(HttpTrait.class);

        if (trait.isPresent()) {
            var segments = trait.get().getUri().getSegments();
            String path = getPath(segments);
            String lowerCasedName = Character.toLowerCase(operationName.charAt(0)) + operationName.substring(1);
            OperationReference ref = new OperationReference(
                    trait.get().getMethod() + " " + path,
                    handlerPath,
                    functionId,
                    lowerCasedName,
                    resourceName
            );

            operationUnion.add(ref);

            writer.openBlock("$L: {", lowerCasedName);
            writer.write("path: '$L $L',", trait.get().getMethod(), path);
            writer.write("handlerPath: $S,", handlerPath);
            writer.write("functionId: $S,", functionId);
            writer.write("operationName: $S,", lowerCasedName);

            writer.closeBlock("},");

        }

    }

    @Override
    public void customize(TypeScriptCodegenContext codegenContext) {
        var delegator = codegenContext.writerDelegator();
        var service = codegenContext.settings().getService();
        var serviceName = service.getName();

        delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "index.ts").toString(), mainIndexWriter -> {
            mainIndexWriter.write("export * from $S;", "./handlers/index");


            // Do not reference "routes" package in root index.ts otherwise build of local handlers will fail
            delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "routes", "index.ts").toString(), routesIndexTsWriter -> {

                var operations = TopDownIndex.of(codegenContext.model()).getContainedOperations(service);
                var serviceFileName = serviceName.toLowerCase() + "-routes";
                delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "routes", serviceFileName + ".ts").toString(), routesServiceFileWriter -> {

                    routesIndexTsWriter.write("export * from $S;", "./" + serviceFileName);
                    routesServiceFileWriter.write("import { Function as _FUNC, FunctionProps, StackContext, ApiGatewayV1ApiRouteProps, ApiGatewayV1ApiFunctionRouteProps, ApiGatewayV1ApiAuthorizer } from $S;", "sst/constructs");
                    routesServiceFileWriter.write("export type HandlerFunctionPropsRequired = $S | $S | $S", "bind", "permissions", "environment");
                    routesServiceFileWriter.write("export type HandlerFunctionProps<AuthorizerKeys> = Required<Pick<FunctionProps, HandlerFunctionPropsRequired>> & Partial<Omit<FunctionProps, HandlerFunctionPropsRequired>> & Required<Pick<ApiGatewayV1ApiFunctionRouteProps<AuthorizerKeys>, $S>>", "authorizer");
                    routesServiceFileWriter.write("export type HandlerProps<AuthorizerKeys> = HandlerFunctionProps<AuthorizerKeys>");

                    routesServiceFileWriter.openBlock("export type OperationDefinition = {");
                    routesServiceFileWriter.write("path: string,");
                    routesServiceFileWriter.write("handlerPath: string,");
                    routesServiceFileWriter.write("functionId: string,");
                    routesServiceFileWriter.write("operationName: string,");
                    routesServiceFileWriter.closeBlock("}");

                    routesServiceFileWriter.write("export type Operations = Record<string, OperationDefinition>;");

                    List<OperationReference> operationList = new ArrayList<>();
                    var resources = TopDownIndex.of(codegenContext.model()).getContainedResources(service);

                    List<ShapeId> resourceOperationShapes = new java.util.ArrayList<>();

                    routesServiceFileWriter.openBlock("const operations: OperationReferences = {");

                    for (var resource : resources) {
                        var resourceName = resource.getId().getName().toLowerCase();
                        var resourceOperations = TopDownIndex.of(codegenContext.model()).getContainedOperations(resource.toShapeId());
                        routesServiceFileWriter.openBlock("$L: {", resourceName);
                        for (var operation : resourceOperations) {
                            resourceOperationShapes.add(operation.toShapeId());

                            writeOperation(resourceName, operation, operationList, routesServiceFileWriter);
                        }
                        routesServiceFileWriter.closeBlock("},");
                    }
                    // Only add "api" resource if there are "global" / api layer operations
                    if (operationList.stream().anyMatch(c -> c.parentResource == "api")) {
                        routesServiceFileWriter.openBlock("api: {");
                        for (var operation : operations) {
                            if (!resourceOperationShapes.contains(operation.toShapeId())) {
                                writeOperation("api", operation, operationList, routesServiceFileWriter);
                            }
                        }
                        routesServiceFileWriter.closeBlock("}");
                    }

                    routesServiceFileWriter.closeBlock("}");

                    StringBuilder operationUnion = new StringBuilder();

                    Map<String, List<OperationReference>> map = operationList.stream().collect(Collectors.groupingBy(OperationReference::getParentResourceFirstCharUppercase, HashMap::new, Collectors.toCollection(ArrayList::new)));

                    for (Map.Entry<String, List<OperationReference>> entry : map.entrySet()) {
                        String resourceName = entry.getKey();
                        routesServiceFileWriter.openBlock("export type $LReference = {", resourceName);
                        for (var operation : entry.getValue()) {
                            routesServiceFileWriter.write("$L: OperationDefinition,", operation.getOperationName());
                        }
                        routesServiceFileWriter.closeBlock("}");
                    }

                    for (var operation : operationList) {
                        if (!operationUnion.isEmpty()) {
                            operationUnion.append(" | ");
                        }
                        operationUnion.append("'").append(operation.getOperationName()).append("'");
                    }

                    routesServiceFileWriter.openBlock("export type OperationReferences = {");

                    for (Map.Entry<String, List<OperationReference>> entry : map.entrySet()) {
                        String resourceName = entry.getKey();
                        routesServiceFileWriter.write("$L: $LReference,", Character.toLowerCase(resourceName.charAt(0)) + resourceName.substring(1), resourceName);
                    }

                    routesServiceFileWriter.closeBlock("}");

                    List<String> resourcesList = new java.util.ArrayList<>();

                    for (var entry : map.entrySet()) {
                        String resourceName = entry.getKey();
                        resourcesList.add(resourceName);
                        routesServiceFileWriter.openBlock("export type $LResourceOperationHandlers<AuthorizerKeys> = {", resourceName);
                        for (var operation : entry.getValue()) {
                            routesServiceFileWriter.write("$L: () => HandlerProps<AuthorizerKeys>,", operation.getOperationName());
                        }
                        routesServiceFileWriter.closeBlock("}");
                    }

                    for (var operation : operationList) {
                        if (!operationUnion.isEmpty()) {
                            operationUnion.append(" | ");
                        }
                        operationUnion.append("'").append(operation.getOperationName()).append("'");
                    }

                    routesServiceFileWriter.openBlock("export type OperationHandlers<AuthorizerKeys> = {");

                    for (var resourceName : resourcesList) {
                        routesServiceFileWriter.write("$L: $LResourceOperationHandlers<AuthorizerKeys>,", Character.toLowerCase(resourceName.charAt(0)) + resourceName.substring(1), resourceName);
                    }

                    routesServiceFileWriter.closeBlock("}");

                    routesServiceFileWriter.write("export type ApiRoutes<AuthorizerKeys> = OperationHandlers<AuthorizerKeys>");
                    routesServiceFileWriter.write("export type BoundRoute<AuthorizerKeys> = Record<$L, () => HandlerProps<AuthorizerKeys>>;", operationUnion.toString());

                    routesServiceFileWriter.openBlock("export class $LHandler<Authorizers extends Record<string, ApiGatewayV1ApiAuthorizer> = Record<string, never>, AuthorizerKeys = keyof Authorizers> {", serviceName);
                    routesServiceFileWriter.write("_routesHandler: ApiRoutes<AuthorizerKeys>;");
                    routesServiceFileWriter.write("stackContext: StackContext;");
                    routesServiceFileWriter.write("authorizers: Authorizers;");
                    routesServiceFileWriter.write("isDeployedStage: (stage: string | undefined) => boolean;");
                    routesServiceFileWriter.openBlock("constructor(context: StackContext, authorizers: Authorizers, isDeployedStageHandler: (stage: string | undefined) => boolean, routesHandler: ApiRoutes<AuthorizerKeys>) {");
                    routesServiceFileWriter.write("this.stackContext = context;");
                    routesServiceFileWriter.write("this.authorizers = authorizers;");
                    routesServiceFileWriter.write("this.isDeployedStage = isDeployedStageHandler;");
                    routesServiceFileWriter.write("this._routesHandler = routesHandler;");
                    routesServiceFileWriter.closeBlock("}");

                    routesServiceFileWriter.openBlock("apiFunctionName = (functionId: string) =>");
                    routesServiceFileWriter.closeBlock("this.stackContext.stack.stage + '-' + this.stackContext.app.name + '-' + functionId;");


                    routesServiceFileWriter.openBlock("apiFunctionDefaultProps(): FunctionProps {");
                    routesServiceFileWriter.openBlock("return {");
                    routesServiceFileWriter.write("timeout: '30 seconds',");
                    routesServiceFileWriter.writeDocs("Make log retention dependent on if the stage is a deployed production/staging or dev stage");
                    routesServiceFileWriter.write("logRetention: this.isDeployedStage(this.stackContext.stack.stage) ? undefined : 'two_weeks'");
                    routesServiceFileWriter.closeBlock("};");
                    routesServiceFileWriter.closeBlock("};");


                    routesServiceFileWriter.openBlock(" createApiFunction(functionId: string, props: FunctionProps): _FUNC {");
                    routesServiceFileWriter.openBlock(" return new _FUNC(this.stackContext.stack, functionId, {");
                    routesServiceFileWriter.write("functionName: this.apiFunctionName(functionId),");
                    routesServiceFileWriter.write("...this.apiFunctionDefaultProps(),");
                    routesServiceFileWriter.write("...props,");
                    routesServiceFileWriter.closeBlock("});");
                    routesServiceFileWriter.closeBlock("};");

                    routesServiceFileWriter.openBlock("createRecordForDefinition(ref: OperationDefinition, handlerProps: HandlerProps<AuthorizerKeys>) {");
                    routesServiceFileWriter.openBlock("return {");
                    routesServiceFileWriter.write("authorizer: handlerProps.authorizer,");
                    routesServiceFileWriter.openBlock("function: this.createApiFunction(ref.functionId, {");
                    routesServiceFileWriter.write("handler: ref.handlerPath,");
                    routesServiceFileWriter.write("...handlerProps");
                    routesServiceFileWriter.closeBlock("}),");
                    routesServiceFileWriter.closeBlock("}");
                    routesServiceFileWriter.closeBlock("}");

                    routesServiceFileWriter.openBlock("handlers(): Record<string, ApiGatewayV1ApiRouteProps<AuthorizerKeys>> {");
                    routesServiceFileWriter.openBlock("return {");
                    for (var operation : operationList) {
                        routesServiceFileWriter.write("'$L': this.createRecordForDefinition(operations.$L.$L, this._routesHandler.$L.$L()),", operation.getPath(), operation.getParentResource(), operation.getOperationName(), operation.getParentResource(), operation.getOperationName());
                    }


                    routesServiceFileWriter.closeBlock("}");
                    routesServiceFileWriter.closeBlock("}");
                    routesServiceFileWriter.closeBlock("}");

                    delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "handlers", "base.ts").toString(), baseWriter -> {
                        baseWriter.write("import { APIGatewayProxyHandler, Context } from $L;", "'aws-lambda'");
                        baseWriter.write("import { Operation, ServiceHandler } from $L;", "'@aws-smithy/server-common'");
                        baseWriter.openBlock("export interface ApiGatewayHandlerBase<T extends Context> {");
                        baseWriter.write("handle(service: ServiceHandler<T>): APIGatewayProxyHandler");
                        baseWriter.closeBlock("}");
                    });

                    delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "server", "controller", "index.ts").toString(), controllerIndex -> {

                        delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "server", "controller", "base.ts").toString(), controllerBaseWriter -> {
                            controllerBaseWriter.write("import { Context } from 'aws-lambda';");
                            controllerBaseWriter.write("import { Operation} from '@aws-smithy/server-common';");
                            controllerBaseWriter.write("import { ApiGatewayHandlerBase } from $L;", "'../../handlers/index'");
                            controllerBaseWriter.openBlock("export interface ControllerConfig<T extends Context> {");
                            controllerBaseWriter.write("gatewayHandler: ApiGatewayHandlerBase<T>;");
                            controllerBaseWriter.write("operationTransformation<I, O>(): (o: Operation<I, O, T>) => Operation<I, O, T>;");
                            controllerBaseWriter.closeBlock("}");
                        });

                        controllerIndex.write("export { ControllerConfig } from $S;", "./base");
                        mainIndexWriter.write("export * from './server/controller';");

                        delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "handlers", "index.ts").toString(), handlerIndex -> {
                            handlerIndex.write("export * from $S;", "./base");

                            for (var entry : map.entrySet()) {
                                var operationsForResource = entry.getValue();

                                if (!operationsForResource.isEmpty()) {
                                    var firstOperation = operationsForResource.getFirst();
                                    var resourceNameUppercase = firstOperation.getParentResourceFirstCharUppercase();
                                    var resourceName = firstOperation.getParentResource();
                                    var handlerImportUnion = operationsForResource
                                            .stream()
                                            .map(s -> s.getOperationName() + "HandlerBuilder")
                                            .collect(Collectors.joining(", ", "", ", ApiGatewayHandlerBase"));

                                    var operationServerImportUnion = operationsForResource
                                            .stream()
                                            .map(s -> s.getOperationNameFirstCharUppercase() + "ServerInput," + s.getOperationNameFirstCharUppercase() + "ServerOutput")
                                            .collect(Collectors.joining(", ", "", ""));


                                    delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "server", "controller", resourceName + "Controller.ts").toString(), controllerWriter -> {
                                        controllerIndex.write("export { $LController } from $S;", resourceNameUppercase, "./" + resourceName + "Controller");
                                        controllerWriter.write("import { Context, APIGatewayProxyHandler } from 'aws-lambda';");
                                        controllerWriter.write("import { Operation} from '@aws-smithy/server-common';");
                                        controllerWriter.write("import { ControllerConfig } from './base';");
                                        controllerWriter.write("import { $L} from $L;", operationServerImportUnion, "'../operations/index'");
                                        controllerWriter.write("import { $L} from $L;", handlerImportUnion, "'../../handlers/index'");
                                        controllerWriter.openBlock("export abstract class $LController<T extends Context> {", resourceNameUppercase);
                                        controllerWriter.write("abstract config: ControllerConfig<T>;");

                                        for (var operation : operationsForResource) {
                                            var uppercasedOperationName = operation.getOperationNameFirstCharUppercase();

                                            handlerIndex.write("export { $LHandlerBuilder } from $S;", operation.getOperationName(), "./" + operation.getOperationName() + "Handler");


                                            delegator.useFileWriter(Paths.get(CodegenUtils.SOURCE_FOLDER, "handlers", operation.getOperationName() + "Handler.ts").toString(), handlerWriter -> {

                                                handlerWriter.write("import { ApiGatewayHandlerBase } from $L;", "'./base'");
                                                handlerWriter.write("import { get$LHandler, $LServerInput, $LServerOutput } from $L;", uppercasedOperationName, uppercasedOperationName, uppercasedOperationName, "'../index'");
                                                handlerWriter.write("import { Context } from $L;", "'aws-lambda'");
                                                handlerWriter.write("import { Operation } from $L;", "'@aws-smithy/server-common'");

                                                handlerWriter.openBlock("export function $LHandlerBuilder<T extends Context>(" +
                                                        "gatewayHandler: ApiGatewayHandlerBase<T>,\n" +
                                                        "op: Operation<$LServerInput, $LServerOutput, T>,\n" +
                                                        "operationTransformation?: (op: Operation<$LServerInput, $LServerOutput, T>) => Operation<$LServerInput, $LServerOutput, T>\n" +
                                                        ") {", operation.getOperationName(), uppercasedOperationName, uppercasedOperationName, uppercasedOperationName, uppercasedOperationName, uppercasedOperationName, uppercasedOperationName);
                                                handlerWriter.write("var operation = operationTransformation ? operationTransformation(op) : op;");
                                                handlerWriter.write("return gatewayHandler.handle(get$LHandler(operation));", uppercasedOperationName);
                                                handlerWriter.closeBlock("}");
                                            });

                                            controllerWriter.write("protected abstract $LFunction<SC extends T>(): Operation<$LServerInput, $LServerOutput, SC>;", operation.getOperationName(), uppercasedOperationName, uppercasedOperationName);
                                            controllerWriter.openBlock("$LHandler(): APIGatewayProxyHandler {", operation.getOperationName());
                                            controllerWriter.write("return this._$LHandler(this.config.gatewayHandler, this.config.operationTransformation());", operation.getOperationName());
                                            controllerWriter.closeBlock("}");
                                            controllerWriter.openBlock("protected _$LHandler(gatewayHandler: ApiGatewayHandlerBase<T>, operationTransformation?: (op: Operation<$LServerInput, $LServerOutput, T>) => Operation<$LServerInput, $LServerOutput, T>): APIGatewayProxyHandler {", operation.getOperationName(), uppercasedOperationName, uppercasedOperationName, uppercasedOperationName, uppercasedOperationName);
                                            controllerWriter.write("return $LHandlerBuilder(gatewayHandler, this.$LFunction(), operationTransformation);", operation.getOperationName(), operation.getOperationName());
                                            controllerWriter.closeBlock("}");
                                        }

                                        controllerWriter.closeBlock("}");
                                    });

                                }


                            }

                        });
                    });
                });
            });
        });

    }

    private static String getPath(List<SmithyPattern.Segment> segments) {
        StringBuilder path = new StringBuilder();
        for (var segment : segments) {
            path.append("/");
            if (segment.isLabel() || segment.isGreedyLabel() || segment.isNonGreedyLabel()) {
                path.append("{").append(segment.getContent()).append("}");
            } else if (segment.isLiteral()) {
                path.append(segment.getContent());
            } else {
                path.append(segment.getContent());
            }
        }
        return path.toString();
    }

}