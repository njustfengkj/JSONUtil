package org.kopitubruk.util.json;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class builds a map of a reflected object.
 *
 * @author Bill Davidson
 */
class ReflectedObjectMapBuilder
{
    /*
     * Data specific to the object being reflected.
     */
    private Object propertyValue;
    private JSONConfig cfg;
    private JSONReflectedClass refClass = null;
    private Class<?> clazz;
    private Collection<String> fieldNames;
    private Map<String,Field> fields;
    private Map<String,Method> getterMethods;
    private ReflectionData reflectionData;
    private Map<ReflectionData,ReflectionData> reflectionDataCache;
    private int privacyLevel;
    private boolean cacheReflectionData;
    private boolean isFieldsSpecified;
    private boolean isPrivate;

    /**
     * Create a ReflectedObjectBuilder for the given object.
     *
     * @param propertyValue The object to be reflected.
     * @param cfg the config object.
     */
    ReflectedObjectMapBuilder( Object propertyValue, JSONConfig cfg )
    {
        this.propertyValue = propertyValue;
        this.cfg = cfg;
    }

    /**
     * Use reflection to build a map of the properties of the given object
     *
     * @return A map representing the object's fields.
     */
    Map<String,Object> buildReflectedObjectMap()
    {
        String name = "buildReflectedObjectMap()";

        try {
            init();
            Map<String,Object> obj;

            if ( reflectionData == null ){
                List<AccessibleObject> attributeList = null;
                List<String> nameList = null;
                obj = new LinkedHashMap<>();
                if ( cacheReflectionData ){
                    attributeList = new ArrayList<>();
                    nameList = new ArrayList<>();
                }

                // populate the object map.
                for ( String fieldName : fieldNames ){
                    name = refClass.getFieldAlias(fieldName);
                    Field field = fields.get(fieldName);
                    Method getter = getGetter(field, fieldName);
                    if ( getter != null ){
                        ReflectUtil.ensureAccessible(getter);
                        obj.put(name, getter.invoke(propertyValue));
                        if ( cacheReflectionData ){
                            nameList.add(name);
                            attributeList.add(getter);
                        }
                    }else if ( field != null && isVisible(field) ){
                        ReflectUtil.ensureAccessible(field);
                        obj.put(name, field.get(propertyValue));
                        if ( cacheReflectionData ){
                            nameList.add(name);
                            attributeList.add(field);
                        }
                    }else if ( isFieldsSpecified ){
                        // field name specified but field/pseudo-field does not exist.
                        throw new JSONReflectionException(propertyValue, fieldName, cfg);
                    }
                }

                if ( cacheReflectionData ){
                    addReflectionData(attributeList, nameList);
                }
            }else{
                /*
                 * Use cached reflection data for better performance. At this
                 * point, all getter availability and privacy level filtration
                 * has been done.  Only have to apply the reflection to the
                 * object with the list of attributes, which have all been made
                 * accessible by the first run that created the cached data.
                 */
                String[] names = reflectionData.getNames();
                AccessibleObject[] attributes = reflectionData.getAttributes();
                obj = new LinkedHashMap<>(attributes.length);

                // populate the object map.
                for ( int i = 0; i < attributes.length; i++ ){
                    AccessibleObject attribute = attributes[i];
                    name = names[i];
                    if ( attribute instanceof Method ){
                        obj.put(name, ((Method)attribute).invoke(propertyValue));
                    }else{
                        obj.put(name, ((Field)attribute).get(propertyValue));
                    }
                }
            }
            return obj;
        }catch ( JSONReflectionException e ){
            throw e;
        }catch ( Exception e ){
            throw new JSONReflectionException(propertyValue, name, e, cfg);
        }
    }

    /**
     * Initialize data for the reflection.
     */
    private void init()
    {
        if ( refClass != null ){
            return;     // already ran
        }
        refClass = cfg.ensureReflectedClass(propertyValue);
        clazz = refClass.getObjClass();
        fieldNames = refClass.getFieldNamesRaw();
        isFieldsSpecified = fieldNames != null;
        privacyLevel = isFieldsSpecified ? ReflectUtil.PRIVATE : cfg.getReflectionPrivacy();
        cacheReflectionData = cfg.isCacheReflectionData();
        if ( cacheReflectionData ){
            Map<String,String> fieldAliases = refClass.getFieldAliases();
            reflectionDataCache = getReflectionDataCache();
            reflectionData = reflectionDataCache.get(new ReflectionData(clazz, fieldNames, fieldAliases, privacyLevel));
        }else{
            reflectionData = null;
        }
        if ( reflectionData == null ){
            // data needed if caching is disabled or hasn't happened yet.
            isPrivate = privacyLevel == ReflectUtil.PRIVATE;
            initFields();
            initGetterMethods();
        }
    }

    /**
     * Initialize the map of the fields for the class and fieldNames
     * if they were not specified.
     */
    private void initFields()
    {
        fields = new HashMap<>();

        Set<String> allFieldNames = new HashSet<>();
        Set<String> fieldNameSet = null;
        if ( isFieldsSpecified ){
            fieldNameSet = (Set<String>)fieldNames;
        }else{
            fieldNames = new ArrayList<>();
        }

        Class<?> tmpClass = clazz;
        while ( tmpClass != null ){
            for ( Field field : tmpClass.getDeclaredFields() ){
                String name = field.getName();
                if ( ! allFieldNames.contains(name) ){
                    allFieldNames.add(name);
                    if ( isFieldsSpecified ){
                        if ( fieldNameSet.contains(name) ){
                            // only get the fields that were specified.
                            fields.put(name, field);
                        }
                    }else{
                        int modifiers = field.getModifiers();
                        if ( Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) ){
                            continue;       // ignore static and transient fields.
                        }
                        fieldNames.add(name);
                        fields.put(name, field);    // direct access or check against getter return type.
                    }
                }
            }
            tmpClass = tmpClass.getSuperclass();
        }
    }

    /**
     * Initialize the map of getter methods for the class.
     */
    private void initGetterMethods()
    {
        getterMethods = new HashMap<>();

        Class<?> tmpClass = clazz;
        while ( tmpClass != null ){
            for ( Method method : tmpClass.getDeclaredMethods() ){
                if ( method.getParameterCount() != 0 ){
                    continue;   // no parameters in bean getter.
                }
                Class<?> retType = method.getReturnType();
                if ( Void.TYPE == retType ){
                    continue;   // getters must return something.
                }
                String name = method.getName();
                if ( getterMethods.containsKey(name) || ! ReflectUtil.GETTER.matcher(name).matches() ){
                    continue;   // don't have this name and name starts with "get" or "is"
                }
                if ( name.startsWith("is") && ! ReflectUtil.isType(ReflectUtil.BOOLEANS, retType) ){
                    continue;   // "is" prefix only valid getter for booleans.
                }
                if ( isVisible(method) ){
                    getterMethods.put(name, method);
                }
            }
            tmpClass = tmpClass.getSuperclass();
        }
    }

    /**
     * Get the getter for the given field.
     *
     * @param field The field
     * @param name The field name.
     * @return The getter or null if one cannot be found.
     */
    private Method getGetter( Field field, String name )
    {
        Method getter = getterMethods.get(ReflectUtil.makeBeanMethodName(name,"get"));
        if ( getter == null ){
            getter = getterMethods.get(ReflectUtil.makeBeanMethodName(name,"is"));
        }
        if ( field == null || getter == null ){
            return getter;
        }else{
            return isCompatible(field, getter) ? getter : null;
        }
    }

    /**
     * Return true if the type returned by the method is compatible in JSON
     * with the type of the field.
     *
     * @param field The field.
     * @param method The method to check the return type of.
     * @return true if they are compatible in JSON.
     */
    private static boolean isCompatible( Field field, Method method )
    {
        Class<?> fieldType = field.getType();
        Class<?> methodType = method.getReturnType();

        if ( fieldType == methodType ){
            return true;    // can't get more compatible than the exact same type.
        }else{
            Class<?>[] methodTypes = ReflectUtil.getTypes(methodType);

            if ( ReflectUtil.isType(methodTypes, fieldType) ){
                // fieldType is a super class or interface of methodType
                return true;
            }

            // check for JSON level compatibility, which is much looser.
            Class<?>[] fieldTypes = ReflectUtil.getTypes(fieldType);
            Class<?>[] t1, t2;
            // check the shorter list first.
            if ( fieldTypes.length < methodTypes.length ){
                t1 = fieldTypes;  t2 = methodTypes;
            }else{
                t1 = methodTypes; t2 = fieldTypes;
            }

            if ( ReflectUtil.isJSONNumber(t1) )       return ReflectUtil.isJSONNumber(t2);
            else if ( ReflectUtil.isJSONString(t1) )  return ReflectUtil.isJSONString(t2);
            else if ( ReflectUtil.isJSONBoolean(t1) ) return ReflectUtil.isJSONBoolean(t2);
            else if ( ReflectUtil.isJSONArray(t1) )   return ReflectUtil.isJSONArray(t2);
            else if ( ReflectUtil.isJSONMap(t1) )     return ReflectUtil.isJSONMap(t2);
            else return false;
        }
    }


    /**
     * Return true if the given field or method is visible with the current
     * privacy level.
     *
     * @param member the field or method.
     * @return true if the field or method is visible.
     */
    private boolean isVisible( Member member )
    {
        return isPrivate || ReflectUtil.getPrivacyLevel(member.getModifiers()) >= privacyLevel;
    }

    /**
     * Add reflection data to the cache.
     *
     * @param attributeList The list of reflected attributes.
     * @param nameList The list of names.
     */
    private void addReflectionData( List<AccessibleObject> attributeList, List<String> nameList )
    {
        // save the reflection data so that it can be used later for fast operation.
        List<String> fnames = isFieldsSpecified ? new ArrayList<>(fieldNames) : null;
        Map<String,String> fieldAliases = refClass.getFieldAliases();
        if ( fieldAliases != null ){
            fieldAliases = new HashMap<>(fieldAliases);
        }
        String[] names = nameList.toArray(new String[nameList.size()]);
        AccessibleObject[] attributes = attributeList.toArray(new AccessibleObject[attributeList.size()]);

        reflectionData = new ReflectionData(clazz, fnames, fieldAliases, privacyLevel, names, attributes);
        reflectionDataCache.put(reflectionData, reflectionData);
    }

    /*
     * static cache for reflection information.
     */
    private static volatile Map<ReflectionData,ReflectionData> REFLECTION_DATA_CACHE;

    /**
     * Clear the reflection cache.
     */
    static synchronized void clearReflectionCache()
    {
        REFLECTION_DATA_CACHE = null;
    }

    /**
     * Get the reflection data cache.
     *
     * @return reflection data cache.
     */
    private static synchronized Map<ReflectionData,ReflectionData> getReflectionDataCache()
    {
        if ( REFLECTION_DATA_CACHE == null ){
            REFLECTION_DATA_CACHE = new Hashtable<>(0);
        }
        return REFLECTION_DATA_CACHE;
    }

    static {
        clearReflectionCache();
    }
}
