package ru.blackfan.bfscan.parsing.httprequests;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.api.ResourcesLoader;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.utils.exceptions.JadxException;
import jadx.core.xmlgen.ResContainer;
import java.io.InputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.XMLConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import ru.blackfan.bfscan.helpers.Helpers;
import ru.blackfan.bfscan.parsing.httprequests.processors.AnnotationUtils;

public class ResourceProcessor {

    private static final Logger logger = LoggerFactory.getLogger(ResourceProcessor.class);

    private final JadxDecompiler jadx;
    private final DocumentBuilderFactory factory;
    private final String apiBasePath;
    private final String apiHost;

    public ResourceProcessor(JadxDecompiler jadx, URI apiUrl) {
        this.jadx = jadx;
        this.apiBasePath = apiUrl.getPath();
        this.apiHost = apiUrl.getHost();
        this.factory = DocumentBuilderFactory.newInstance();
        this.factory.setValidating(false);
        try {
            this.factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            this.factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            this.factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            this.factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException ex) {
            logger.error("ParserConfigurationException", ex);
        }
    }

    public List<MultiHTTPRequest> processResources() {
        List<MultiHTTPRequest> multiRequests = new ArrayList();
        for (ResourceFile resFile : jadx.getResources()) {
            ResContainer resContainer = resFile.loadContent();
            ResContainer.DataType dataType = resContainer.getDataType();

            try {
                if (dataType == ResContainer.DataType.RES_LINK) {
                    ResourcesLoader.decodeStream(resFile, (size, is) -> {
                        multiRequests.addAll(processFile(resFile.getDeobfName(), is));
                        return null;
                    });
                }
                if (dataType == ResContainer.DataType.TEXT) {
                    InputStream resourceStream = Helpers.getResourceInputStream(resFile, resContainer);
                    if (resourceStream != null) {
                        multiRequests.addAll(processFile(resFile.getDeobfName(), resourceStream));
                    }
                }
            } catch (JadxException ex) {
                logger.error("Error processing file " + resFile.getDeobfName(), ex);
            }
        }
        return multiRequests;
    }

    public List<MultiHTTPRequest> processFile(String name, InputStream is) {
        List<MultiHTTPRequest> multiRequests = new ArrayList<>();
        
        if (name.toLowerCase().endsWith("routes")) {
            multiRequests.addAll(processPlayRoutes(name, is));
            return multiRequests;
        }
        
        switch (Helpers.getFileExtension(name)) {
            case "xml" -> {
                multiRequests.addAll(processXml(name, is));
            }
            case "zip", "jar" -> {
                try {
                    ZipFile zip = Helpers.inputSteamToZipFile(is);
                    List<ZipEntry> entries = (List<ZipEntry>) Collections.list(zip.entries());

                    List<MultiHTTPRequest> results = entries.parallelStream()
                            .filter(entry -> !entry.isDirectory())
                            .map(entry -> {
                                try (InputStream zipEntryIs = zip.getInputStream(entry)) {
                                    return processFile(name + "#" + entry.getName(), zipEntryIs);
                                } catch (IOException ex) {
                                    logger.error("Error processing zip entry " + entry.getName() + " in " + name, ex);
                                    return Collections.<MultiHTTPRequest>emptyList();
                                }
                            })
                            .flatMap(List::stream)
                            .collect(Collectors.toList());

                    multiRequests.addAll(results);

                    try {
                        zip.close();
                    } catch (IOException ex) {
                        logger.warn("Error closing zip file " + name, ex);
                    }
                } catch (IOException ex) {
                    logger.error("Error processing file " + name, ex);
                }
            }
        }
        return multiRequests;
    }

    private List<MultiHTTPRequest> processXml(String name, InputStream is) {
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(is);
            Element root = document.getDocumentElement();

            String rootTagName = root.getTagName();

            if (null != rootTagName) {
                switch (rootTagName) {
                    case "web-app", "web-fragment" -> {
                        return processWebXml(name, document);
                    }
                    case "struts-config" -> {
                        return processStrutsConfigXml(name, document);
                    }
                    case "struts" -> {
                        return processStrutsXml(name, document);
                    }
                    case "Configure" -> {
                        return processJettyXml(name, document);
                    }
                    case "beans" -> {
                        return processSpringBeansXml(name, document);
                    }
                    default -> {
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Error parsing requests from " + name, ex);
        }
        return new ArrayList();
    }

    private List<MultiHTTPRequest> processWebXml(String name, Document document) {
        List<MultiHTTPRequest> multiRequests = new ArrayList();
        try {
            Map<String, String> servletClasses = new HashMap<>();

            NodeList servlets = document.getElementsByTagName("servlet");
            for (int i = 0; i < servlets.getLength(); i++) {
                Element servlet = (Element) servlets.item(i);
                String servletName = getElementText(servlet, "servlet-name");
                String servletClassOrJsp = getElementText(servlet, "servlet-class");
                if (servletClassOrJsp.isEmpty()) {
                    servletClassOrJsp = getElementText(servlet, "jsp-file");
                }
                if (!servletName.isEmpty() && !servletClassOrJsp.isEmpty()) {
                    servletClasses.put(servletName, servletClassOrJsp);
                }
            }

            NodeList mappings = document.getElementsByTagName("servlet-mapping");
            for (int i = 0; i < mappings.getLength(); i++) {
                Element mapping = (Element) mappings.item(i);
                String servletName = getElementText(mapping, "servlet-name");
                NodeList urlPatterns = mapping.getElementsByTagName("url-pattern");
                for (int j = 0; j < urlPatterns.getLength(); j++) {
                    String urlPattern = urlPatterns.item(j).getTextContent();
                    String servletClass = servletClasses.getOrDefault(servletName, "UNKNOWN");

                    MultiHTTPRequest webXmlRequest = new MultiHTTPRequest(apiHost, apiBasePath, servletClass, name);
                    webXmlRequest.addAdditionalInformation("web.xml Servlet");
                    webXmlRequest.setPath(urlPattern, false);

                    if (!servletClass.equals("UNKNOWN")) {
                        List<String> httpMethods = AnnotationUtils.extractHttpMethodsFromServletClass(jadx.getRoot(), servletClass);
                        if (!httpMethods.isEmpty()) {
                            webXmlRequest.setMethods(httpMethods);
                        }
                    }

                    multiRequests.add(webXmlRequest);
                }
            }
        } catch (Exception ex) {
            logger.error("Error parsing requests from web.xml", ex);
        }
        return multiRequests;
    }

    private List<MultiHTTPRequest> processStrutsConfigXml(String name, Document document) {
        List<MultiHTTPRequest> multiRequests = new ArrayList();
        try {
            Map<String, String> forms = new HashMap();
            Map<String, Map<String, Object>> dynaFormsParameters = new HashMap();
            NodeList formBeansList = document.getElementsByTagName("form-beans");
            if (formBeansList.getLength() > 0) {
                Element formBeansNode = (Element) formBeansList.item(0);
                NodeList formBeans = formBeansNode.getElementsByTagName("form-bean");
                for (int i = 0; i < formBeans.getLength(); i++) {
                    Element formBean = (Element) formBeans.item(i);
                    String formName = formBean.getAttribute("name");
                    String formType = formBean.getAttribute("type");
                    if (!formName.isEmpty() && !formType.isEmpty() && !formType.equals(Constants.Struts.VALID_FORM)) {
                        forms.put(formName, formType);
                        if (Constants.Struts.DYNA_FORM.contains(formType)) {
                            Map<String, Object> formsParameters = new HashMap();
                            NodeList formProperties = formBean.getElementsByTagName("form-property");
                            for (int j = 0; j < formProperties.getLength(); j++) {
                                Element formProperty = (Element) formProperties.item(j);
                                String propertyName = formProperty.getAttribute("name");
                                String propertyType = formProperty.getAttribute("type");
                                if (!propertyName.isEmpty() && !propertyType.isEmpty()) {
                                    if (propertyType.endsWith("[]")) {
                                        propertyType = propertyType.substring(0, propertyType.length() - 2);
                                    }
                                    formsParameters.put(propertyName, AnnotationUtils.classNameToDefaultValue(propertyName, propertyType, jadx.getRoot(), new HashSet<>(), false));
                                }
                            }
                            dynaFormsParameters.put(formName, formsParameters);
                        }
                    }
                }
            }

            NodeList mappings = document.getElementsByTagName("action-mappings");
            if (mappings.getLength() > 0) {
                Element mappingsNode = (Element) mappings.item(0);
                NodeList actions = mappingsNode.getElementsByTagName("action");
                for (int i = 0; i < actions.getLength(); i++) {
                    Element action = (Element) actions.item(i);
                    String path = action.getAttribute("path");
                    String type = action.getAttribute("type");
                    String actionName = action.getAttribute("name");
                    if (type.isEmpty()) {
                        type = "unknown-class";
                    }
                    if (!path.isEmpty()) {
                        MultiHTTPRequest strutsXmlRequest = new MultiHTTPRequest(apiHost, apiBasePath, type, name);
                        strutsXmlRequest.addAdditionalInformation("Struts Config Action");
                        strutsXmlRequest.setPath(path + ".action", false);
                        if (forms.containsKey(actionName)) {
                            final String formClass = forms.get(actionName);
                            Map<String, Object> parameters = null;
                            if (!Constants.Struts.DYNA_FORM.contains(formClass)) {
                                if (!formClass.isEmpty()) {
                                    ClassNode classNode = Helpers.loadClass(jadx.getRoot(), formClass);
                                    if (classNode != null) {
                                        parameters = AnnotationUtils.classToRequestParameters(classNode, false, jadx.getRoot());
                                    }
                                }
                            } else {
                                if (dynaFormsParameters.containsKey(actionName)) {
                                    Map<String, Object> formParameters = dynaFormsParameters.get(actionName);
                                    if (!formParameters.isEmpty()) {
                                        parameters = formParameters;
                                    }
                                }
                            }
                            AnnotationUtils.appendParametersToRequest(strutsXmlRequest, parameters);
                        }
                        multiRequests.add(strutsXmlRequest);
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Error parsing requests from struts-config.xml", ex);
        }
        return multiRequests;
    }

    private List<MultiHTTPRequest> processStrutsXml(String name, Document document) {
        List<MultiHTTPRequest> multiRequests = new ArrayList();
        try {
            NodeList packages = document.getElementsByTagName("package");
            for (int i = 0; i < packages.getLength(); i++) {
                Element pkg = (Element) packages.item(0);
                NodeList actions = pkg.getElementsByTagName("action");
                for (int j = 0; j < actions.getLength(); j++) {
                    Element action = (Element) actions.item(j);
                    String actionName = action.getAttribute("name");
                    String actionClass = action.getAttribute("class");
                    if (!actionName.isEmpty()) {
                        Map<String, Object> parameters = new HashMap();
                        if (!actionClass.isEmpty()) {
                            ClassNode classNode = Helpers.loadClass(jadx.getRoot(), actionClass);
                            if (classNode != null) {
                                parameters = AnnotationUtils.classToRequestParameters(classNode, false, jadx.getRoot());
                            }
                        } else {
                            actionClass = "unknown-class";
                        }

                        MultiHTTPRequest strutsXmlRequest = new MultiHTTPRequest(apiHost, apiBasePath, actionClass, name);
                        strutsXmlRequest.addAdditionalInformation("Struts Action");
                        strutsXmlRequest.setPath(actionName + ".action", false);
                        AnnotationUtils.appendParametersToRequest(strutsXmlRequest, parameters);
                        multiRequests.add(strutsXmlRequest);
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Error parsing requests from struts.xml", ex);
        }
        return multiRequests;
    }

    /* Support only the simplest syntax for adding servlets */
    private List<MultiHTTPRequest> processJettyXml(String name, Document document) {
        List<MultiHTTPRequest> multiRequests = new ArrayList<>();

        try {
            NodeList calls = document.getElementsByTagName("Call");
            for (int i = 0; i < calls.getLength(); i++) {
                Element call = (Element) calls.item(i);
                if ("addServlet".equals(call.getAttribute("name")) || "addServletWithMapping".equals(call.getAttribute("name"))) {
                    NodeList args = call.getElementsByTagName("Arg");
                    if (args.getLength() >= 2) {
                        if (!hasElementChildren((Element) args.item(0)) && !hasElementChildren((Element) args.item(1))) {
                            String servletName = args.item(0).getTextContent();
                            String urlPattern = args.item(1).getTextContent();

                            MultiHTTPRequest jettyRequest = new MultiHTTPRequest(apiHost, apiBasePath, servletName, name);
                            jettyRequest.addAdditionalInformation("jetty.xml Servlet");
                            jettyRequest.setPath(urlPattern, false);
                            multiRequests.add(jettyRequest);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Error parsing requests from jetty.xml", ex);
        }
        return multiRequests;
    }

    private boolean hasElementChildren(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() == Node.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    private static String getElementText(Element element, String tagName) {
        NodeList nodeList = element.getElementsByTagName(tagName);
        if (nodeList.getLength() > 0) {
            return nodeList.item(0).getTextContent().trim();
        }
        return "";
    }

    private List<MultiHTTPRequest> processPlayRoutes(String name, InputStream is) {
        List<MultiHTTPRequest> multiRequests = new ArrayList<>();
        
        Pattern httpMethodPattern = Pattern.compile("^\\s*(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+", Pattern.CASE_INSENSITIVE);
        
        Pattern modifierPattern = Pattern.compile("^\\s*\\+\\s*(\\w+)");
        
        Pattern includePattern = Pattern.compile("^\\s*->\\s+(\\S+)\\s+(\\S+)");
        
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            String currentModifier = null;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                Matcher modifierMatcher = modifierPattern.matcher(line);
                if (modifierMatcher.find()) {
                    currentModifier = modifierMatcher.group(1);
                    continue;
                }
                
                Matcher includeMatcher = includePattern.matcher(line);
                if (includeMatcher.find()) {
                    String includePrefix = includeMatcher.group(1);
                    String includeRouter = includeMatcher.group(2);
                    
                    MultiHTTPRequest includeRequest = new MultiHTTPRequest(apiHost, apiBasePath, includeRouter, name);
                    includeRequest.addAdditionalInformation("Play Framework Included Routes");
                    includeRequest.setPath(includePrefix, false);
                    includeRequest.setMethod("GET");
                    multiRequests.add(includeRequest);
                    continue;
                }
                
                Matcher httpMethodMatcher = httpMethodPattern.matcher(line);
                if (httpMethodMatcher.find()) {
                    String httpMethod = httpMethodMatcher.group(1).toUpperCase();
                    
                    String remainingLine = line.substring(httpMethodMatcher.end()).trim();
                    
                    String[] parts = remainingLine.split("\\s+", 2);
                    if (parts.length >= 2) {
                        String path = parts[0].trim();
                        String controllerPart = parts[1].trim();
                        
                        String controllerAction;
                        String parametersStr = null;
                        
                        int paramStart = controllerPart.indexOf('(');
                        if (paramStart != -1) {
                            controllerAction = controllerPart.substring(0, paramStart).trim();
                            int paramEnd = controllerPart.lastIndexOf(')');
                            if (paramEnd != -1) {
                                parametersStr = controllerPart.substring(paramStart + 1, paramEnd).trim();
                            }
                        } else {
                            controllerAction = controllerPart;
                        }
                        
                        MultiHTTPRequest playRequest = new MultiHTTPRequest(apiHost, apiBasePath, controllerAction, name);
                        playRequest.addAdditionalInformation("Play Framework Route");
                        playRequest.setMethod(httpMethod);
                        String normalizedPath = normalizePlayRoutePath(path);
                        playRequest.setPath(normalizedPath, false);
                        
                        if (currentModifier != null) {
                            playRequest.addAdditionalInformation("Modifier: " + currentModifier);
                            currentModifier = null;
                        }
                        
                        processPathParameters(playRequest, path);
                        
                        if (parametersStr != null && !parametersStr.isEmpty() && !controllerAction.contains("controllers.Assets")) {
                            processControllerParameters(playRequest, parametersStr);
                        }
                        
                        multiRequests.add(playRequest);
                    }
                }
            }
        } catch (IOException ex) {
            logger.error("Error processing Play Framework routes file " + name, ex);
        }
        
        return multiRequests;
    }
    
    private void processPathParameters(MultiHTTPRequest request, String path) {
        Pattern pathParamPattern = Pattern.compile("([:*])(\\w+)|\\$(\\w+)<([^>]+)>");
        Matcher matcher = pathParamPattern.matcher(path);
        
        while (matcher.find()) {
            String paramType = matcher.group(1);
            String paramName = matcher.group(2);
            String regexParamName = matcher.group(3);
            String regex = matcher.group(4);
            
            if (paramName != null) {
                if (":".equals(paramType)) {
                    request.addPathParameter(paramName);
                } else if ("*".equals(paramType)) {
                    request.addPathParameter(paramName);
                }
            }
            
            if (regexParamName != null && regex != null) {
                request.addPathParameter(regexParamName);
            }
        }
    }
    
    private void processControllerParameters(MultiHTTPRequest request, String parametersStr) {
        if (parametersStr == null || parametersStr.trim().isEmpty()) {
            return;
        }
        
        List<String> params = splitParameters(parametersStr);
        
        for (String param : params) {
            param = param.trim();
            if (param.isEmpty()) continue;
            
            String paramName;
            String paramType = "String";
            Object defaultValue = null;
            
            if (param.contains("?=")) {
                String[] parts = param.split("\\?=", 2);
                String leftPart = parts[0].trim();
                String rightPart = parts[1].trim();
                
                if (rightPart.startsWith("\"") && rightPart.endsWith("\"")) {
                    rightPart = rightPart.substring(1, rightPart.length() - 1);
                }
                defaultValue = rightPart;
                
                if (leftPart.contains(":")) {
                    String[] typeParts = leftPart.split(":", 2);
                    paramName = typeParts[0].trim();
                    paramType = typeParts[1].trim();
                } else {
                    paramName = leftPart;
                }
            }
            else if (param.contains("=")) {
                String[] nameValue = param.split("=", 2);
                paramName = nameValue[0].trim();
                String value = nameValue[1].trim();
                
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                defaultValue = value;
            }
            else if (param.contains(":")) {
                String[] parts = param.split(":", 2);
                paramName = parts[0].trim();
                paramType = parts[1].trim();
                defaultValue = getDefaultValueForType(paramType);
            }
            else {
                paramName = param;
                defaultValue = getDefaultValueForType(paramType);
            }
            
            if (!request.getPathParameters().contains(paramName) && defaultValue != null) {
                request.putQueryParameter(paramName, defaultValue.toString());
            }
        }
    }
    
    private List<String> splitParameters(String parametersStr) {
        List<String> params = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inQuotes = false;
        
        for (char c : parametersStr.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (!inQuotes) {
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                    current.append(c);
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                    current.append(c);
                } else if (c == ',' && depth == 0) {
                    params.add(current.toString().trim());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            } else {
                current.append(c);
            }
        }
        
        if (current.length() > 0) {
            params.add(current.toString().trim());
        }
        
        return params;
    }
    
    private Object getDefaultValueForType(String paramType) {
        paramType = paramType.toLowerCase().trim();
        return switch (paramType) {
            case "string" -> "example_string";
            case "int", "integer" -> 1;
            case "long" -> 1L;
            case "boolean", "bool" -> false;
            case "double" -> 1.0;
            case "float" -> 1.0f;
            case "request" -> null;
            default -> paramType.contains("request") ? null : "value";
        };
    }
    
    private String normalizePlayRoutePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        
        String normalizedPath = path;
        normalizedPath = normalizedPath.replaceAll(":(\\w+)", "{$1}");
        normalizedPath = normalizedPath.replaceAll("\\*(\\w+)", "{$1}");
        normalizedPath = normalizedPath.replaceAll("\\$(\\w+)<[^>]+>", "{$1}");
        
        return normalizedPath;
    }

    private List<MultiHTTPRequest> processSpringBeansXml(String name, Document document) {
        List<MultiHTTPRequest> multiRequests = new ArrayList<>();
        try {
            Map<String, String> beanIdToClass = new HashMap<>();
            
            NodeList allBeans = document.getElementsByTagName("bean");
            for (int i = 0; i < allBeans.getLength(); i++) {
                Element bean = (Element) allBeans.item(i);
                String beanId = bean.getAttribute("id");
                String beanName = bean.getAttribute("name");
                String beanClass = bean.getAttribute("class");
                
                String beanIdentifier = beanId.isEmpty() ? beanName : beanId;
                if (!beanIdentifier.isEmpty()) {
                    beanIdToClass.put(beanIdentifier, beanClass != null ? beanClass : "");
                }
            }
            
            for (int i = 0; i < allBeans.getLength(); i++) {
                Element bean = (Element) allBeans.item(i);
                String beanClass = bean.getAttribute("class");
                
                if ("org.springframework.web.servlet.handler.SimpleUrlHandlerMapping".equals(beanClass)) {
                    NodeList properties = bean.getElementsByTagName("property");
                    for (int j = 0; j < properties.getLength(); j++) {
                        Element property = (Element) properties.item(j);
                        String propertyName = property.getAttribute("name");
                        
                        if ("mappings".equals(propertyName)) {
                            NodeList valueElements = property.getElementsByTagName("value");
                            if (valueElements.getLength() > 0) {
                                String mappingsText = valueElements.item(0).getTextContent();
                                parseSimpleUrlHandlerMappings(mappingsText, beanIdToClass, multiRequests, name);
                            }
                        }
                    }
                }
            }
            
            parseBeanNameUrlHandlerMappings(beanIdToClass, multiRequests, name);
        } catch (Exception ex) {
            logger.error("Error parsing requests from Spring beans XML", ex);
        }
        return multiRequests;
    }

    private void parseSimpleUrlHandlerMappings(String mappingsText, Map<String, String> beanIdToClass, 
                                                List<MultiHTTPRequest> multiRequests, String fileName) {
        if (mappingsText == null || mappingsText.trim().isEmpty()) {
            return;
        }
        
        String[] lines = mappingsText.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            int equalsIndex = line.indexOf('=');
            if (equalsIndex > 0) {
                String path = line.substring(0, equalsIndex).trim();
                String controllerBeanId = line.substring(equalsIndex + 1).trim();
                
                if (!path.isEmpty() && !controllerBeanId.isEmpty()) {
                    String controllerClass = beanIdToClass.getOrDefault(controllerBeanId, controllerBeanId);
                    
                    MultiHTTPRequest springRequest = new MultiHTTPRequest(apiHost, apiBasePath, controllerClass, fileName);
                    springRequest.addAdditionalInformation("Spring SimpleUrlHandlerMapping");
                    springRequest.setPath(path, false);
                    multiRequests.add(springRequest);
                }
            }
        }
    }

    private void parseBeanNameUrlHandlerMappings(Map<String, String> beanIdToClass, 
                                                 List<MultiHTTPRequest> multiRequests, String fileName) {
        for (Map.Entry<String, String> entry : beanIdToClass.entrySet()) {
            String beanId = entry.getKey();
            String beanClass = entry.getValue();
            
            if (beanId != null) {
                String[] beanNames = beanId.split(",");
                List<String> urlPaths = new ArrayList<>();
                
                for (String beanName : beanNames) {
                    beanName = beanName.trim();
                    if (beanName.startsWith("/")) {
                        urlPaths.add(beanName);
                    }
                }
                
                if (!urlPaths.isEmpty()) {
                    String controllerClass = (beanClass != null && !beanClass.isEmpty()) ? beanClass : beanId;
                    
                    if (urlPaths.size() == 1) {
                        MultiHTTPRequest springRequest = new MultiHTTPRequest(apiHost, apiBasePath, controllerClass, fileName);
                        springRequest.addAdditionalInformation("Spring BeanNameUrlHandlerMapping");
                        springRequest.setPath(urlPaths.get(0), false);
                        multiRequests.add(springRequest);
                    } else {
                        MultiHTTPRequest springRequest = new MultiHTTPRequest(apiHost, apiBasePath, controllerClass, fileName);
                        springRequest.addAdditionalInformation("Spring BeanNameUrlHandlerMapping");
                        springRequest.setPaths(urlPaths);
                        multiRequests.add(springRequest);
                    }
                }
            }
        }
    }
}
