package com.jude.ferryman.compiler.generator;

import com.jude.ferryman.compiler.Constants;
import com.jude.ferryman.compiler.model.ActivityInfo;
import com.jude.ferryman.compiler.model.FieldInfo;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.lang.model.element.Modifier;

import static com.jude.ferryman.compiler.Constants.CLASS_BOAT;
import static com.jude.ferryman.compiler.Constants.CLASS_CONTEXT;
import static com.jude.ferryman.compiler.Constants.CLASS_PORTER;
import static com.jude.ferryman.compiler.Constants.CLASS_RESULT_SUFFIX;
import static com.jude.ferryman.compiler.Constants.CLASS_URL;
import static com.jude.ferryman.compiler.Constants.CLASS_WARDEN;
import static com.jude.ferryman.compiler.Constants.PACKAGE_MAIN;

/**
 * Created by zhuchenxi on 2017/1/20.
 */

public class BoatGenerator extends ClassGenerator {

    public static final String METHOD_FROM = "from";


    public static final String PARAMETER_CONTEXT = "context";

    public static final String METHOD_API_PREFIX = "goto";


    private ArrayList<ActivityInfo> mActivityInfos;

    public BoatGenerator(ArrayList<ActivityInfo> mActivityInfos) {
        super(PACKAGE_MAIN, CLASS_BOAT);
        this.mActivityInfos = mActivityInfos;
    }

    @Override
    public JavaFile build() {
        TypeSpec.Builder result =
                TypeSpec.classBuilder(getClassName())
                        .addModifiers(Modifier.PUBLIC);
        addContextField(result);
        addConstructor(result);
        addAPIMethod(result);


        return JavaFile.builder(getPackageName(), result.build())
                .addFileComment("Generated class from Ferryman. Do not modify!")
                .build();
    }

    private void addContextField(TypeSpec.Builder result) {
        result.addField(
                FieldSpec.builder(ClassName.bestGuess(CLASS_CONTEXT), PARAMETER_CONTEXT)
                        .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                        .build()
        );
    }

    private void addConstructor(TypeSpec.Builder result) {
        MethodSpec constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.bestGuess(CLASS_CONTEXT), PARAMETER_CONTEXT)
                .addStatement("this.$N = $N", PARAMETER_CONTEXT, PARAMETER_CONTEXT)
                .build();
        result.addMethod(constructor);
    }

    private void addAPIMethod(TypeSpec.Builder result) {
        for (ActivityInfo activityInfo : mActivityInfos) {
            String activitySimpleName = activityInfo.getName().simpleName();

            // 如果没有result，就无泛型
            TypeName returnType = null;
            TypeName resultType = null;

            if (activityInfo.getResult().isEmpty()) {
                returnType = ClassName.bestGuess(CLASS_WARDEN);
            } else {
                resultType = ClassName.get(activityInfo.getName().packageName(), activitySimpleName + CLASS_RESULT_SUFFIX);
                returnType = ParameterizedTypeName.get(ClassName.bestGuess(CLASS_WARDEN), resultType);
            }

            // 区分模板方法
            Map<String, List<FieldInfo>> methods = divideMethod(activityInfo.getParams());
            for (Map.Entry<String, List<FieldInfo>> fieldListEntry : methods.entrySet()) {
                String suffix = fieldListEntry.getKey();
                if (!suffix.isEmpty()){
                    suffix = suffix.replaceFirst(suffix.charAt(0) + "", Character.toUpperCase(suffix.charAt(0)) + "");
                }
                MethodSpec method = MethodSpec.methodBuilder(METHOD_API_PREFIX + activitySimpleName + suffix)
                        .addModifiers(Modifier.PUBLIC)
                        .addJavadoc("springboard of {@link $T}\n", activityInfo.getName())
                        .returns(returnType)
                        .build();

                // 区分可空方法
                List<List<FieldInfo>> ignoreMethods = divideIgnoreMethod(fieldListEntry.getValue());
                for (List<FieldInfo> ignoreMethod : ignoreMethods) {
                    // 最终区分了模板与可空之后的方法
                    MethodSpec.Builder methodBuilder = method.toBuilder();

                    methodBuilder
                            .addStatement("$T.Builder builder = new $T.Builder()", ClassName.bestGuess(CLASS_URL), ClassName.bestGuess(CLASS_URL))
                            .addStatement("builder.setAddress($S)", activityInfo.getUrl()[0]);
                    addField(methodBuilder, ignoreMethod);
                    addConverterCode(methodBuilder, ignoreMethod);
                    addReturnCode(methodBuilder, resultType, !activityInfo.getResult().isEmpty());

                    result.addMethod(methodBuilder.build());
                }

            }

        }
    }

    /**
     * 在 Map 中保存所有分类的参数列表，同时维持一个主列表
     * 每读入一个普通参数，给每一个列表+1。
     * 每读到一个分组参数，给分组对应列表+1.
     *      如果分组不存在，从主列表里fork一个列表作为此分组列表
     *
     * @param fieldInfoList
     * @return
     */
    private Map<String, List<FieldInfo>> divideMethod(List<FieldInfo> fieldInfoList) {
        // key是参数列表，value是方法标识
        HashMap<String, List<FieldInfo>> fieldMap = new HashMap<>();
        List<FieldInfo> base = new ArrayList<>();

        for (FieldInfo fieldInfo : fieldInfoList) {
            String[] methods = fieldInfo.getMethod();

            if (methods == null || methods.length == 0 || (methods.length == 1 && methods[0].isEmpty())) {
                base.add(fieldInfo);
                for (List<FieldInfo> fieldInfos : fieldMap.values()) {
                    fieldInfos.add(fieldInfo);
                }
            } else {
                for (String method : methods) {
                    List<FieldInfo> list = fieldMap.get(method);
                    if (list == null) {
                        list = new ArrayList<>(base);
                        fieldMap.put(method, list);
                    }
                    list.add(fieldInfo);
                }
            }
        }
        if (fieldMap.isEmpty()){
            return Collections.singletonMap("",base);
        }else {
            return fieldMap;
        }
    }

    private List<List<FieldInfo>> divideIgnoreMethod(List<FieldInfo> fieldInfoList) {
        List<FieldInfo> baseInfoList = new ArrayList<>();
        List<FieldInfo> ignoreInfoList = new ArrayList<>();
        for (FieldInfo fieldInfo : fieldInfoList) {
            if (fieldInfo.isIgnore()) {
                ignoreInfoList.add(fieldInfo);
            } else {
                baseInfoList.add(fieldInfo);
            }
        }
        List<List<FieldInfo>> result = new ArrayList<>();
        int len = ignoreInfoList.size();
        int nbits = 1 << len;
        for (int i = 0; i < nbits; ++i) {
            int t;
            ArrayList<FieldInfo> curFields = new ArrayList<>(baseInfoList);
            for (int j = 0; j < len; j++) {
                t = 1 << j;
                if ((t & i) != 0) {
                    curFields.add(ignoreInfoList.get(j));
                }
            }
            result.add(curFields);
        }
        return result;
    }


    /**
     * 添加方法参数
     *
     * @param methodBuilder
     * @param fieldInfoList
     */
    private void addField(MethodSpec.Builder methodBuilder, List<FieldInfo> fieldInfoList) {
        for (FieldInfo fieldInfo : fieldInfoList) {
            ParameterSpec.Builder parameterSpecBuilder = ParameterSpec.builder(fieldInfo.getClazz(), fieldInfo.getKey());
            for (AnnotationSpec annotationSpec : fieldInfo.getAnnotations()) {
                parameterSpecBuilder.addAnnotation(annotationSpec);
            }
            methodBuilder.addParameter(parameterSpecBuilder.build());
        }
    }

    /**
     * 添加参数转换代码
     *
     * @param methodBuilder
     * @param fieldInfoList
     */
    private void addConverterCode(MethodSpec.Builder methodBuilder, List<FieldInfo> fieldInfoList) {

        int number = 0;
        for (FieldInfo fieldInfo : fieldInfoList) {
            TypeName typeName = fieldInfo.getClazz();
            generateType(methodBuilder, typeName, number, 0, 0);
            methodBuilder.addStatement("builder.addParam($S, $T.fromObject(type$L$L$L,$L))",
                    fieldInfo.getKey(),
                    ClassName.bestGuess(CLASS_PORTER),
                    number, 0, 0,
                    fieldInfo.getKey());
            number++;
        }
    }

    /**
     * 添加跳转及返回代码
     *
     * @param methodBuilder
     * @param resultType
     * @param hasReturn
     */
    private void addReturnCode(MethodSpec.Builder methodBuilder, TypeName resultType, boolean hasReturn) {
        if (!hasReturn) {
            methodBuilder.addStatement("$T warden = new $T()",
                    ClassName.bestGuess(Constants.CLASS_WARDEN),
                    ClassName.bestGuess(Constants.CLASS_WARDEN));
        } else {
            methodBuilder.addStatement("$T<$T> warden = new $T<>(new $T())",
                    ClassName.bestGuess(Constants.CLASS_WARDEN),
                    resultType,
                    ClassName.bestGuess(Constants.CLASS_WARDEN),
                    resultType);
        }
        methodBuilder.addStatement("$T.startActivityFromAPI($L,builder.build().toString(),warden.innerListener)",
                ClassName.bestGuess(Constants.CLASS_ROUTERDRIVER),
                PARAMETER_CONTEXT
        );
        methodBuilder.addStatement("return warden");
    }


    private void generateType(MethodSpec.Builder builder, TypeName typeName, int number, int index, int deep) {
        TypeName typeToken = ClassName.bestGuess("com.google.gson.reflect.TypeToken");
        TypeName type = ClassName.bestGuess("java.lang.reflect.Type");
        if (typeName instanceof ParameterizedTypeName) {
            for (int i = 0; i < ((ParameterizedTypeName) typeName).typeArguments.size(); i++) {
                generateType(builder, ((ParameterizedTypeName) typeName).typeArguments.get(i), number, i, deep + 1);
            }
            String argString = "";
            for (int i = 0; i < ((ParameterizedTypeName) typeName).typeArguments.size(); i++) {
                argString += ", type" + number + i + (deep + 1);
            }
            builder.addStatement("$T type$L$L$L = $T.getParameterized($T.class" + argString + ").getType()", type, number, index, deep, typeToken, ((ParameterizedTypeName) typeName).rawType);
        } else {
            builder.addStatement("$T type$L$L$L = $T.get($T.class).getType()", type, number, index, deep, typeToken, typeName);
        }
    }

}
