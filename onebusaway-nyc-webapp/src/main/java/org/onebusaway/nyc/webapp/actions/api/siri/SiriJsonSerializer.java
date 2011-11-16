package org.onebusaway.nyc.webapp.actions.api.siri;

import java.io.IOException;
import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.List;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.BeanPropertyDefinition;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.Module;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.annotate.JsonSerialize.Inclusion;
import org.codehaus.jackson.map.introspect.BasicBeanDescription;
import org.codehaus.jackson.map.ser.BeanSerializer;
import org.codehaus.jackson.map.ser.BeanSerializerModifier;
import org.codehaus.jackson.map.ser.std.BeanSerializerBase;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;
import org.springframework.util.ReflectionUtils;

import uk.org.siri.siri.Siri;

public class SiriJsonSerializer {
  
  public static class CustomValueObjectSerializer extends BeanSerializerBase {

    private String fieldName = null;
    
    protected CustomValueObjectSerializer(BeanSerializer src, String fieldName) {
      super(src);
      this.fieldName = fieldName;
    }

    @Override
    public void serialize(Object bean, JsonGenerator jgen,
        SerializerProvider provider) throws IOException, JsonGenerationException {
      
      try {
        Class<? extends Object> beanClass = bean.getClass();
        Field valueField = ReflectionUtils.findField(beanClass, fieldName);
//        Field valueField = beanClass.getDeclaredField(fieldName);
        valueField.setAccessible(true);

        Object value = valueField.get(bean);
  
        provider.defaultSerializeValue(value, jgen);
      } catch(Exception e) {
        jgen.writeNull();
      }
    }
    
  }
  
  public static class CustomBeanSerializerModifier extends BeanSerializerModifier {

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
        BasicBeanDescription beanDesc, JsonSerializer<?> serializer) {
      
      if(serializer instanceof BeanSerializer) {
        List<BeanPropertyDefinition> properties = beanDesc.findProperties();
        for(BeanPropertyDefinition property : properties) {
          if(property.getName().equals("value") || property.getName().equals("any")) {
            String fieldName = property.getField().getName();
            if(fieldName != null)
              return super.modifySerializer(config, beanDesc, new CustomValueObjectSerializer((BeanSerializer)serializer, fieldName));
          }
        }
        
      }
      
      return super.modifySerializer(config, beanDesc, serializer);
    }
  }
  
  public static class JacksonModule extends Module {
    private final static Version VERSION = new Version(1,0,0, null);
    
    @Override
    public String getModuleName() {
      return "CustomSerializer";
    }

    @Override
    public Version version() {
      return VERSION;
    }

    @Override
    public void setupModule(SetupContext context) {
      context.addBeanSerializerModifier(new CustomBeanSerializerModifier());
    }
  }

  public static String getJson(Siri siri) throws Exception {    
    return getJson(siri, null);
  }
  
  public static String getJson(Siri siri, String callback) throws Exception {    
    try {
      ObjectMapper mapper = new ObjectMapper();    
      mapper.setSerializationInclusion(Inclusion.NON_NULL);
      mapper.configure(SerializationConfig.Feature.INDENT_OUTPUT, true);

      DateFormat isoDateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
      mapper.setDateFormat(isoDateFormatter);

      AnnotationIntrospector introspector = new JaxbAnnotationIntrospector();
      SerializationConfig config = mapper.getSerializationConfig().withAnnotationIntrospector(introspector);
      mapper.setSerializationConfig(config);

      mapper.registerModule(new JacksonModule());

      String output = "";
      
      if(callback != null)
        output = callback + "(";
      
      output += mapper.writeValueAsString(siri);

      if(callback != null)
        output += ")";
    
      return output;
    } catch(Exception e) {
      return e.getMessage();
    }
  }  
  
}