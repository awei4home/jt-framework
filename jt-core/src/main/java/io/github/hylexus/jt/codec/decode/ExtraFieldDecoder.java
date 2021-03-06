package io.github.hylexus.jt.codec.decode;

import io.github.hylexus.jt.annotation.msg.req.extra.ExtraField;
import io.github.hylexus.jt.annotation.msg.req.extra.ExtraMsgBody;
import io.github.hylexus.jt.data.converter.ConvertibleMetadata;
import io.github.hylexus.jt.data.converter.DataTypeConverter;
import io.github.hylexus.jt.data.converter.registry.DataTypeConverterRegistry;
import io.github.hylexus.jt.data.converter.registry.DefaultDataTypeConverterRegistry;
import io.github.hylexus.jt.data.msg.NestedFieldMappingInfo;
import io.github.hylexus.jt.mata.JavaBeanFieldMetadata;
import io.github.hylexus.jt.mata.JavaBeanMetadata;
import io.github.hylexus.jt.utils.JavaBeanMetadataUtils;
import io.github.hylexus.oaks.utils.Bytes;
import io.github.hylexus.oaks.utils.IntBitOps;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author hylexus
 * Created At 2019-10-01 7:39 下午
 */
@Slf4j
public class ExtraFieldDecoder {

    private final DataTypeConverterRegistry dataTypeConverterRegistry = new DefaultDataTypeConverterRegistry();
    private static final ConcurrentMap<Class<?>, ConcurrentMap<Integer, NestedFieldMappingInfo>> cache = new ConcurrentHashMap<>();
    private final SplittableFieldDecoder splittableFieldDecoder = new SplittableFieldDecoder();
    private final SlicedFromDecoder slicedFromDecoder = new SlicedFromDecoder();

    public void decodeExtraField(
            byte[] bytes, int startIndex, int length, Object instance, JavaBeanFieldMetadata fieldMetadata)
            throws IllegalAccessException, InstantiationException {

        final Class<?> extraFieldClass = fieldMetadata.getFieldType();

        Object extraFieldInstance = extraFieldClass.newInstance();
        fieldMetadata.getField().setAccessible(true);
        fieldMetadata.getField().set(instance, extraFieldInstance);

        Map<Integer, NestedFieldMappingInfo> mappingInfo = getMappingInfo(extraFieldClass);
        ExtraMsgBody extraMsgBodyInfo = extraFieldClass.getAnnotation(ExtraMsgBody.class);
        int byteCountOfMsgId = extraMsgBodyInfo.byteCountOfMsgId();
        int byteCountOfContentLength = extraMsgBodyInfo.byteCountOfContentLength();
        decodeNestedField(
                bytes, startIndex, length, extraFieldInstance, mappingInfo,
                byteCountOfMsgId, byteCountOfContentLength
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void decodeNestedField(
            byte[] bytes, int startIndex, int length, Object instance, Map<Integer, NestedFieldMappingInfo> mappingInfo,
            int byteCountOfMsgId, int byteCountOfContentLength) throws IllegalAccessException, InstantiationException {

        int readerIndex = startIndex;
        while (readerIndex < length) {
            int msgId = IntBitOps.intFromBytes(bytes, readerIndex, byteCountOfMsgId);
            readerIndex += byteCountOfMsgId;

            final NestedFieldMappingInfo info = mappingInfo.get(msgId);
            if (info == null) {
                continue;
            }

            int bodyLength = IntBitOps.intFromBytes(bytes, readerIndex, byteCountOfContentLength);
            readerIndex += byteCountOfContentLength;

            byte[] bodyBytes = Bytes.subSequence(bytes, readerIndex, bodyLength);
            readerIndex += bodyLength;

            if (info.isNestedExtraField()) {
                Map<Integer, NestedFieldMappingInfo> map = getMappingInfo(info.getFieldMetadata().getFieldType());

                ExtraMsgBody ex = info.getFieldMetadata().getFieldType().getAnnotation(ExtraMsgBody.class);

                Object newInstance = info.getFieldMetadata().getFieldType().newInstance();
                info.getFieldMetadata().setFieldValue(instance, newInstance);

                decodeNestedField(bodyBytes, 0, bodyBytes.length, newInstance, map, ex.byteCountOfMsgId(), ex.byteCountOfContentLength());
            } else {
                // TODO auto-inject
                ConvertibleMetadata key = ConvertibleMetadata.forJt808MsgDataType(info.getDataType(), info.getFieldMetadata().getFieldType());
                Optional<DataTypeConverter<?, ?>> converterInfo = dataTypeConverterRegistry.getConverter(key);
                if (converterInfo.isPresent()) {
                    DataTypeConverter converter = converterInfo.get();
                    Object value = converter.convert(byte[].class, info.getFieldMetadata().getFieldType(), bodyBytes);
                    // fix https://github.com/hylexus/jt-framework/issues/2
                    info.getFieldMetadata().setFieldValue(instance, value);
                    splittableFieldDecoder.processSplittableField(instance, info.getFieldMetadata(), value);
                } else {
                    log.error("No converter found for filed {}", info.getFieldMetadata().getFieldType().getName());
                }
                //                Object value = populateBasicField(bodyBytes, instance, info.getFieldMetadata(), info.getDataType(), 0,
                //                        bodyBytes.length);
                //                splittableFieldDecoder.processSplittableField(instance, info.getFieldMetadata(), value);
            }
        }
        slicedFromDecoder.processAllSlicedFromField(instance);
    }

    private Map<Integer, NestedFieldMappingInfo> getMappingInfo(Class<?> cls) {
        final ConcurrentMap<Integer, NestedFieldMappingInfo> cachedMappingInfo = cache.get(cls);
        if (cachedMappingInfo != null) {
            return cachedMappingInfo;
        }

        final ConcurrentMap<Integer, NestedFieldMappingInfo> newInfo = buildNestedMappingInfo(cls);
        cache.put(cls, newInfo);
        return newInfo;
    }

    private ConcurrentMap<Integer, NestedFieldMappingInfo> buildNestedMappingInfo(Class<?> cls) {
        final ConcurrentMap<Integer, NestedFieldMappingInfo> mappingInfo = new ConcurrentHashMap<>();

        final ExtraMsgBody bodyProps = cls.getAnnotation(ExtraMsgBody.class);
        final JavaBeanMetadata beanMetadata = JavaBeanMetadataUtils.getBeanMetadata(cls);
        for (JavaBeanFieldMetadata metadata : beanMetadata.getFieldMetadataList()) {
            ExtraField.NestedFieldMapping nestedMetadata = metadata.getField().getAnnotation(ExtraField.NestedFieldMapping.class);
            if (nestedMetadata == null) {
                continue;
            }
            NestedFieldMappingInfo info = new NestedFieldMappingInfo();
            info.setMsgId(nestedMetadata.msgId());
            info.setDataType(nestedMetadata.dataType());
            info.setNestedExtraField(nestedMetadata.isNestedExtraField());
            info.setByteCountOfMsgId(bodyProps.byteCountOfMsgId());
            info.setByteCountOfContentLength(bodyProps.byteCountOfContentLength());
            info.setFieldMetadata(metadata);
            mappingInfo.put(nestedMetadata.msgId(), info);
        }
        return mappingInfo;
    }
}
