package io.onedev.server.plugin.executor.kubernetes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.Maps;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecuteResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.server.OneDev;
import io.onedev.server.OneException;
import io.onedev.server.ci.job.CacheSpec;
import io.onedev.server.ci.job.EnvVar;
import io.onedev.server.ci.job.JobContext;
import io.onedev.server.ci.job.JobService;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.RegistryLogin;
import io.onedev.server.model.support.administration.jobexecutor.JobExecutor;
import io.onedev.server.model.support.administration.jobexecutor.NodeSelectorEntry;
import io.onedev.server.model.support.administration.jobexecutor.ServiceLocator;
import io.onedev.server.model.support.inputspec.SecretInput;
import io.onedev.server.plugin.executor.kubernetes.KubernetesExecutor.TestData;
import io.onedev.server.util.JobLogger;
import io.onedev.server.util.PKCS12CertExtractor;
import io.onedev.server.util.ServerConfig;
import io.onedev.server.util.validation.annotation.DnsName;
import io.onedev.server.web.editable.annotation.Editable;
import io.onedev.server.web.editable.annotation.Horizontal;
import io.onedev.server.web.editable.annotation.NameOfEmptyValue;
import io.onedev.server.web.editable.annotation.OmitName;
import io.onedev.server.web.util.Testable;

@Editable(order=100, description="This executor runs CI jobs as pods in a kubernetes cluster")
@Horizontal
public class KubernetesExecutor extends JobExecutor implements Testable<TestData> {

	private static final long serialVersionUID = 1L;
	
	private static final Logger logger = LoggerFactory.getLogger(KubernetesExecutor.class);
	
	private static final int MAX_AFFINITY_WEIGHT = 10; 
	
	private static final String CACHE_LABEL_PREFIX = "onedev-cache/";
	
	private static final int LABEL_UPDATE_BATCH = 100;
	
	private List<NodeSelectorEntry> nodeSelector = new ArrayList<>();
	
	private String namespacePrefix = "onedev-ci";
	
	private String serviceAccount;
	
	private List<RegistryLogin> registryLogins = new ArrayList<>();
	
	private List<ServiceLocator> serviceLocators = new ArrayList<>();

	private String configFile;
	
	private String kubeCtlPath;
	
	private boolean createCacheLabels = true;

	@Editable(order=20, description="Optionally specify node selector of the job pods")
	public List<NodeSelectorEntry> getNodeSelector() {
		return nodeSelector;
	}

	public void setNodeSelector(List<NodeSelectorEntry> nodeSelector) {
		this.nodeSelector = nodeSelector;
	}

	@Editable(order=30, description="OneDev will create a separate kubernetes namespace to run each "
			+ "job for isolation purpose. Here you may specify prefix of the namespace to identify "
			+ "various job resources created by this executor")
	@DnsName
	@NotEmpty
	public String getNamespacePrefix() {
		return namespacePrefix;
	}

	public void setNamespacePrefix(String namespacePrefix) {
		this.namespacePrefix = namespacePrefix;
	}
	
	@Editable(order=40, description="Optionally specify a service account in above namespace to run the job "
			+ "pod. Refer to <a href='https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/'>"
			+ "kubernetes documentation</a> on how to set up service accounts")
	public String getServiceAccount() {
		return serviceAccount;
	}

	public void setServiceAccount(String serviceAccount) {
		this.serviceAccount = serviceAccount;
	}

	@Editable(order=200, description="Specify login information of docker registries if necessary. "
			+ "These logins will be used to create image pull secrets of the job pods")
	public List<RegistryLogin> getRegistryLogins() {
		return registryLogins;
	}

	public void setRegistryLogins(List<RegistryLogin> registryLogins) {
		this.registryLogins = registryLogins;
	}

	@Editable(order=25000, group="More Settings", description="Optionally specify where to run service pods "
			+ "specified in job. The first matching locator will be used. If no any locators are found, "
			+ "node selector of the executor will be used")
	public List<ServiceLocator> getServiceLocators() {
		return serviceLocators;
	}

	public void setServiceLocators(List<ServiceLocator> serviceLocators) {
		this.serviceLocators = serviceLocators;
	}

	@Editable(name="Kubectl Config File", order=26000, group="More Settings", description=
			"Specify absolute path to the config file used by kubectl to access the "
			+ "cluster. Leave empty to have kubectl determining cluster access "
			+ "information automatically")
	@NameOfEmptyValue("Use default")
	public String getConfigFile() {
		return configFile;
	}
 
	public void setConfigFile(String configFile) {
		this.configFile = configFile;
	}

	@Editable(name="Path to kubectl", order=27000, group="More Settings", description=
			"Specify absolute path to the kubectl utility, for instance: <i>/usr/bin/kubectl</i>. "
			+ "If left empty, OneDev will try to find the utility from system path")
	@NameOfEmptyValue("Use default")
	public String getKubeCtlPath() {
		return kubeCtlPath;
	}

	public void setKubeCtlPath(String kubeCtlPath) {
		this.kubeCtlPath = kubeCtlPath;
	}

	@Editable(order=60000, group="More Settings", description="If enabled, OneDev will create labels "
			+ "on nodes to record cache count for each cache key in job definition, and then "
			+ "leverage Kubernetes node affinity feature to improve job cache hit rate. Note that "
			+ "many labels might be created on node if there are many cache keys")
	public boolean isCreateCacheLabels() {
		return createCacheLabels;
	}

	public void setCreateCacheLabels(boolean createCacheLabels) {
		this.createCacheLabels = createCacheLabels;
	}

	@Override
	public void execute(String jobToken, JobContext jobContext) {
		execute(jobContext.getImage(), jobToken, jobContext.getLogger(), jobContext);
	}
	
	@Override
	public void test(TestData testData, JobLogger jobLogger) {
		execute(testData.getDockerImage(), KubernetesResource.TEST_JOB_TOKEN, jobLogger, null);
	}
	
	private Commandline newKubeCtl() {
		String kubectl = getKubeCtlPath();
		if (kubectl == null)
			kubectl = "kubectl";
		Commandline cmdline = new Commandline(kubectl); 
		if (getConfigFile() != null)
			cmdline.addArgs("--kubeconfig", getConfigFile());
		return cmdline;
	}
	
	private String createResource(Map<Object, Object> resourceDef, Collection<String> secretsToMask, JobLogger jobLogger) {
		Commandline kubectl = newKubeCtl();
		File file = null;
		try {
			AtomicReference<String> resourceNameRef = new AtomicReference<String>(null);
			file = File.createTempFile("k8s", ".yaml");
			
			String resourceYaml = new Yaml().dump(resourceDef);
			
			String maskedYaml = resourceYaml;
			for (String secret: secretsToMask) 
				maskedYaml = StringUtils.replace(maskedYaml, secret, SecretInput.MASK);
			logger.trace("Creating resource:\n" + maskedYaml);
			
			FileUtils.writeFile(file, resourceYaml, Charsets.UTF_8.name());
			kubectl.addArgs("create", "-f", file.getAbsolutePath(), "-o", "jsonpath={.metadata.name}");
			kubectl.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					resourceNameRef.set(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log("Kubernetes: " + line);
				}
				
			}).checkReturnCode();
			
			return Preconditions.checkNotNull(resourceNameRef.get());
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			if (file != null)
				file.delete();
		}
	}
	
	private void deleteNamespace(String namespace, JobLogger jobLogger) {
		Commandline cmd = newKubeCtl();
		cmd.addArgs("delete", "namespace", namespace);
		cmd.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.debug(line);
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log("Kubernetes: " + line);
			}
			
		}).checkReturnCode();
	}
	
	private String createNamespace(@Nullable JobContext jobContext, JobLogger jobLogger) {
		String namespace = getNamespacePrefix() + "-";
		if (jobContext != null)
			namespace += jobContext.getProjectName().replace('.', '-').replace('_', '-') + "-" + jobContext.getBuildNumber();
		else
			namespace += "executor-test";
		
		AtomicBoolean namespaceExists = new AtomicBoolean(false);
		Commandline cmd = newKubeCtl();
		cmd.addArgs("get", "namespaces", "--field-selector", "metadata.name=" + namespace, 
				"-o", "name");
		cmd.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				namespaceExists.set(true);
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log("Kubernetes: " + line);
			}
			
		}).checkReturnCode();
		
		if (namespaceExists.get())
			deleteNamespace(namespace, jobLogger);
		
		cmd.clearArgs();
		cmd.addArgs("create", "namespace", namespace);
		cmd.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.debug(line);
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log("Kubernetes: " + line);
			}
			
		}).checkReturnCode();
		
		return namespace;
	}
	
	@Nullable
	private Map<Object, Object> getAffinity(@Nullable JobContext jobContext) {
		Map<Object, Object> nodeAffinity = new LinkedHashMap<>();
		
		List<Object> matchExpressions = new ArrayList<>();
		for (NodeSelectorEntry selector: getNodeSelector()) {
			matchExpressions.add(Maps.newLinkedHashMap(
					"key", selector.getLabelName(), 
					"operator", "In", 
					"values", Lists.newArrayList(selector.getLabelValue())));
		}
		if (!matchExpressions.isEmpty()) {
			List<Object> nodeSelectorTerms = Lists.<Object>newArrayList(
					Maps.newLinkedHashMap("matchExpressions", matchExpressions));
			nodeAffinity.put("requiredDuringSchedulingIgnoredDuringExecution", 
					Maps.newLinkedHashMap("nodeSelectorTerms", nodeSelectorTerms));
		} 
		
		if (jobContext != null) {
			List<Object> preferredDuringSchedulingIgnoredDuringExecution = new ArrayList<>();
			for (CacheSpec cacheSpec: jobContext.getCacheSpecs()) {
				 for (int i=1; i<MAX_AFFINITY_WEIGHT; i++) {
				 preferredDuringSchedulingIgnoredDuringExecution.add(Maps.newLinkedHashMap(
						 "weight", i, 
						 "preference", Maps.newLinkedHashMap("matchExpressions",
								 Lists.<Object>newArrayList(Maps.newLinkedHashMap(
										 "key", CACHE_LABEL_PREFIX + cacheSpec.getKey(), 
										 "operator", "In", 
										 "values", Lists.newArrayList(String.valueOf(i))))))); 
				 }
				 preferredDuringSchedulingIgnoredDuringExecution.add(Maps.newLinkedHashMap(
						"weight", MAX_AFFINITY_WEIGHT,
						"preference", Maps.newLinkedHashMap(
								"matchExpressions", Lists.<Object>newArrayList(Maps.newLinkedHashMap(
										"key", CACHE_LABEL_PREFIX + cacheSpec.getKey(), 
										"operator", "Gt", 
										"values", Lists.newArrayList(String.valueOf(MAX_AFFINITY_WEIGHT-1)))))));
			}
			if (!preferredDuringSchedulingIgnoredDuringExecution.isEmpty())
				nodeAffinity.put("preferredDuringSchedulingIgnoredDuringExecution", preferredDuringSchedulingIgnoredDuringExecution);
		}
		
		if (!nodeAffinity.isEmpty()) 
			return Maps.newLinkedHashMap("nodeAffinity", nodeAffinity);
		else 
			return null;
	}
	
	private OsInfo getBaselineOsInfo(Collection<NodeSelectorEntry> nodeSelector, JobLogger jobLogger) {
		Commandline kubectl = newKubeCtl();
		kubectl.addArgs("get", "nodes", "-o", "jsonpath={range .items[*]}{.status.nodeInfo.operatingSystem} {.status.nodeInfo.kernelVersion} {.spec.unschedulable}{'|'}{end}");
		for (NodeSelectorEntry entry: nodeSelector) 
			kubectl.addArgs("-l", entry.getLabelName() + "=" + entry.getLabelValue());
		
		Collection<OsInfo> osInfos = new ArrayList<>();

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		kubectl.execute(baos, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log("Kubernetes: " + line);
			}
			
		}).checkReturnCode();
		
		for (String osInfoString: Splitter.on('|').trimResults().omitEmptyStrings().splitToList(baos.toString())) {
			osInfoString = osInfoString.replace('\n', ' ').replace('\r', ' ');
			List<String> fields = Splitter.on(' ').omitEmptyStrings().trimResults().splitToList(osInfoString);
			if (fields.size() == 2 || fields.get(2).equals("false"))
				osInfos.add(new OsInfo(fields.get(0), fields.get(1)));
		}

		if (!osInfos.isEmpty()) {
			return OsInfo.getBaseline(osInfos);
		} else {
			throw new OneException("No applicable working nodes found");
		}
	}
	
	private String getServerUrl() {
		return OneDev.getInstance(SettingManager.class).getSystemSetting().getServerUrl();
	}
	
	@Nullable
	private String createImagePullSecret(String namespace, JobLogger jobLogger) {
		if (!getRegistryLogins().isEmpty()) {
			Map<Object, Object> auths = new LinkedHashMap<>();
			for (RegistryLogin login: getRegistryLogins()) {
				String auth = login.getUserName() + ":" + login.getPassword();
				String registryUrl = login.getRegistryUrl();
				if (registryUrl == null)
					registryUrl = "https://index.docker.io/v1/";
				auths.put(registryUrl, Maps.newLinkedHashMap(
						"auth", Base64.encodeBase64String(auth.getBytes(Charsets.UTF_8))));
			}
			ObjectMapper mapper = OneDev.getInstance(ObjectMapper.class);
			try {
				String dockerConfig = mapper.writeValueAsString(Maps.newLinkedHashMap("auths", auths));
				
				String secretName = "image-pull-secret";
				Map<String, String> encodedSecrets = new LinkedHashMap<>();
				Map<Object, Object> secretDef = Maps.newLinkedHashMap(
						"apiVersion", "v1", 
						"kind", "Secret", 
						"metadata", Maps.newLinkedHashMap(
								"name", secretName, 
								"namespace", namespace), 
						"data", Maps.newLinkedHashMap(
								".dockerconfigjson", Base64.encodeBase64String(dockerConfig.getBytes(Charsets.UTF_8))));
				secretDef.put("type", "kubernetes.io/dockerconfigjson");
				createResource(secretDef, encodedSecrets.values(), jobLogger);
				return secretName;
			} catch (JsonProcessingException e) {
				throw new RuntimeException(e);
			}
		} else {
			return null;
		}
	}
	
	@Nullable
	private String createTrustCertsConfigMap(String namespace, JobLogger jobLogger) {
		Map<String, String> configMapData = new LinkedHashMap<>();
		ServerConfig serverConfig = OneDev.getInstance(ServerConfig.class); 
		File keystoreFile = serverConfig.getKeystoreFile();
		if (keystoreFile != null) {
			String password = serverConfig.getKeystorePassword();
			for (Map.Entry<String, String> entry: new PKCS12CertExtractor(keystoreFile, password).extact().entrySet()) 
				configMapData.put(entry.getKey(), entry.getValue());
		}
		File trustCertsDir = serverConfig.getTrustCertsDir();
		if (trustCertsDir != null) {
			for (File file: trustCertsDir.listFiles()) {
				if (file.isFile()) {
					try {
						configMapData.put("specified-cert-" + file.getName(), FileUtils.readFileToString(file));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
			}
		}
		if (!configMapData.isEmpty()) {
			Map<Object, Object> configMapDef = Maps.newLinkedHashMap(
					"apiVersion", "v1", 
					"kind", "ConfigMap",
					"metadata", Maps.newLinkedHashMap(
							"name", "trust-certs", 
							"namespace", namespace), 
					"data", configMapData);
			return createResource(configMapDef, new HashSet<>(), jobLogger);			
		} else {
			return null;
		}
	}
	
	private void startService(String namespace, JobContext jobContext, JobService jobService, 
			@Nullable String imagePullSecretName, JobLogger jobLogger) {
		jobLogger.log("Creating service pod...");
		
		List<NodeSelectorEntry> nodeSelector = getNodeSelector();
		for (ServiceLocator locator: getServiceLocators()) {
			if (locator.isApplicable(jobService)) {
				nodeSelector = locator.getNodeSelector();
				break;
			}
		}
		
		Map<String, Object> podSpec = new LinkedHashMap<>();
		Map<Object, Object> containerSpec = Maps.newHashMap(
				"name", "default", 
				"image", jobService.getImage());
		containerSpec.put("resources", Maps.newLinkedHashMap("requests", Maps.newLinkedHashMap(
				"cpu", jobService.getCpuRequirement(), 
				"memory", jobService.getMemoryRequirement())));
		List<Map<Object, Object>> envs = new ArrayList<>();
		for (EnvVar envVar: jobService.getEnvVars()) {
			envs.add(Maps.newLinkedHashMap(
					"name", envVar.getName(), 
					"value", envVar.getValue()));
		}
		if (jobService.getArguments() != null) {
			List<String> argList = new ArrayList<>();
			for (String arg: StringUtils.parseQuoteTokens(jobService.getArguments()))
				argList.add(arg);
			containerSpec.put("args", argList);			
		}
		containerSpec.put("env", envs);
		
		podSpec.put("containers", Lists.<Object>newArrayList(containerSpec));
		if (imagePullSecretName != null)
			podSpec.put("imagePullSecrets", Lists.<Object>newArrayList(Maps.newLinkedHashMap("name", imagePullSecretName)));
		podSpec.put("restartPolicy", "Never");		
		
		if (!nodeSelector.isEmpty())
			podSpec.put("nodeSelector", toMap(nodeSelector));
		
		String podName = "service-" + jobService.getName();
		
		Map<Object, Object> podDef = Maps.newLinkedHashMap(
				"apiVersion", "v1", 
				"kind", "Pod", 
				"metadata", Maps.newLinkedHashMap(
						"name", podName, 
						"namespace", namespace, 
						"labels", Maps.newLinkedHashMap(
								"service", jobService.getName())), 
				"spec", podSpec);
		createResource(podDef, Sets.newHashSet(), jobLogger);		
		
		Map<Object, Object> serviceDef = Maps.newLinkedHashMap(
				"apiVersion", "v1", 
				"kind", "Service", 
				"metadata", Maps.newLinkedHashMap(
						"name", jobService.getName(),
						"namespace", namespace), 
				"spec", Maps.newLinkedHashMap(
						"clusterIP", "None", 
						"selector", Maps.newLinkedHashMap(
								"service", jobService.getName())));
		createResource(serviceDef, Sets.newHashSet(), jobLogger);
		
		jobLogger.log("Waiting for service to be ready...");
		
		checkEventError(namespace, podName, jobLogger);

		OsInfo baselineOsInfo = getBaselineOsInfo(nodeSelector, jobLogger);
		ObjectMapper mapper = OneDev.getInstance(ObjectMapper.class);
		while (true) {
			Commandline kubectl = newKubeCtl();
			kubectl.addArgs("get", "pod", podName, "-n", namespace, "-o", "json");
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			kubectl.execute(baos, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log("Kubernetes: " + line);
				}
				
			}).checkReturnCode();

			JsonNode statusNode;
			try {
				statusNode = mapper.readTree(baos.toString()).get("status");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			
			JsonNode containerStatusesNode = statusNode.get("containerStatuses");
			if (containerStatusesNode != null) {
				JsonNode containerStatusNode = containerStatusesNode.iterator().next();
				JsonNode stateNode = containerStatusNode.get("state");
				if (stateNode.get("running") != null) {
					kubectl.clearArgs();
					kubectl.addArgs("exec", podName, "-n", namespace, "--");
					if (baselineOsInfo.isLinux())
						kubectl.addArgs("bash", "-c");
					else 
						kubectl.addArgs("cmd.exe", "/c");
					kubectl.addArgs(jobService.getReadinessCheckCommand());
					ExecuteResult result = kubectl.execute(new LineConsumer() {

						@Override
						public void consume(String line) {
							jobLogger.log("Service readiness check: " + line);
						}
						
					}, new LineConsumer() {

						@Override
						public void consume(String line) {
							jobLogger.log("Service readiness check: " + line);
						}
						
					});
					if (result.getReturnCode() == 0) {
						jobLogger.log("Service is ready");
						break;
					}
				} else if (stateNode.get("terminated") != null) {
					JsonNode terminatedNode = stateNode.get("terminated");							
					JsonNode reasonNode = terminatedNode.get("reason");
					String reason = null;
					if (reasonNode != null && !reasonNode.asText().equals("Error") && !reasonNode.asText().equals("Completed"))
						reason = reasonNode.asText();

					JsonNode messageNode = terminatedNode.get("message");
					if (messageNode != null) { 
						if (reason != null)
							reason += ": " + messageNode.asText();
						else
							reason = messageNode.asText();
					}
					
					if (reason != null)
						jobLogger.log(reason);
					
					collectContainerLog(namespace, podName, "default", null, jobLogger);
					String message = "Service '" + jobService.getName() + "' is stopped unexpectedly";
					throw new OneException(message);
				}
			}
			
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private Map<String, String> toMap(List<NodeSelectorEntry> nodeSelector) {
		Map<String, String> map = new LinkedHashMap<>();
		for (NodeSelectorEntry entry: nodeSelector)
			map.put(entry.getLabelName(), entry.getLabelValue());
		return map;
	}
	
	private void execute(String dockerImage, String jobToken, JobLogger jobLogger, @Nullable JobContext jobContext) {
		jobLogger.log("Checking cluster access...");
		Commandline kubectl = newKubeCtl();
		kubectl.addArgs("cluster-info");
		kubectl.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.debug(line);
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}
			
		}).checkReturnCode();
		
		String namespace = createNamespace(jobContext, jobLogger);
		jobLogger.log("Executing job with kubernetes under namespace " + namespace + "...");
		
		try {
			String imagePullSecretName = createImagePullSecret(namespace, jobLogger);
			if (jobContext != null) {
				for (JobService jobService: jobContext.getServices()) {
					jobLogger.log("Starting service '" + jobService.getName() + "'...");
					startService(namespace, jobContext, jobService, imagePullSecretName, jobLogger);
				}
			}
			
			String trustCertsConfigMapName = createTrustCertsConfigMap(namespace, jobLogger);
			
			OsInfo baselineOsInfo = getBaselineOsInfo(getNodeSelector(), jobLogger);
			
			Map<String, Object> podSpec = new LinkedHashMap<>();
			
			Map<Object, Object> mainContainerSpec = Maps.newHashMap(
					"name", "main", 
					"image", dockerImage);
	
			String k8sHelperClassPath;
			String containerCIHome;
			String containerCacheHome;
			String trustCertsHome;
			String dockerSock;
			if (baselineOsInfo.isLinux()) {
				containerCIHome = "/onedev-ci";
				containerCacheHome = containerCIHome + "/cache";
				trustCertsHome = containerCIHome + "/trust-certs";
				k8sHelperClassPath = "/k8s-helper/*";
				mainContainerSpec.put("command", Lists.newArrayList("sh"));
				mainContainerSpec.put("args", Lists.newArrayList(containerCIHome + "/commands.sh"));
				dockerSock = "/var/run/docker.sock";
			} else {
				containerCIHome = "C:\\onedev-ci";
				containerCacheHome = containerCIHome + "\\cache";
				trustCertsHome = containerCIHome + "\\trust-certs";
				k8sHelperClassPath = "C:\\k8s-helper\\*";
				mainContainerSpec.put("command", Lists.newArrayList("cmd"));
				mainContainerSpec.put("args", Lists.newArrayList("/c", containerCIHome + "\\commands.bat"));
				dockerSock = null;
			}

			Map<String, String> ciHomeMount = Maps.newLinkedHashMap(
					"name", "ci-home", 
					"mountPath", containerCIHome);
			Map<String, String> cacheHomeMount = Maps.newLinkedHashMap(
					"name", "cache-home", 
					"mountPath", containerCacheHome);
			Map<String, String> trustCertsMount = Maps.newLinkedHashMap(
					"name", "trust-certs-home", 
					"mountPath", trustCertsHome);
			Map<String, String> dockerSockMount = Maps.newLinkedHashMap(
					"name", "docker-sock", 
					"mountPath", dockerSock);
			
			List<Object> volumeMounts = Lists.<Object>newArrayList(ciHomeMount, cacheHomeMount);
			if (trustCertsConfigMapName != null)
				volumeMounts.add(trustCertsMount);
			if (dockerSock != null)
				volumeMounts.add(dockerSockMount);
			
			mainContainerSpec.put("volumeMounts", volumeMounts);
			
			if (jobContext != null) {
				mainContainerSpec.put("resources", Maps.newLinkedHashMap("requests", Maps.newLinkedHashMap(
						"cpu", jobContext.getCpuRequirement(), 
						"memory", jobContext.getMemoryRequirement())));
			}
	
			List<Map<Object, Object>> envs = new ArrayList<>();
			envs.add(Maps.newLinkedHashMap(
					"name", KubernetesHelper.ENV_SERVER_URL, 
					"value", getServerUrl()));
			envs.add(Maps.newLinkedHashMap(
					"name", KubernetesHelper.ENV_JOB_TOKEN, 
					"value", jobToken));

			List<String> sidecarArgs = Lists.newArrayList(
					"-classpath", k8sHelperClassPath,
					"io.onedev.k8shelper.SideCar");
			List<String> initArgs = Lists.newArrayList(
					"-classpath", k8sHelperClassPath, 
					"io.onedev.k8shelper.Init");
			if (jobContext == null) {
				sidecarArgs.add("test");
				initArgs.add("test");
			}
			
			String helperImageVersion;
			/*
			try (InputStream is = KubernetesExecutor.class.getClassLoader().getResourceAsStream("k8s-helper-version.properties")) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				IOUtils.copy(is, baos);
				helperImageVersion = baos.toString();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			*/
			
			// TODO: uncomment above for production code
			helperImageVersion = "latest";
			
			Map<Object, Object> sidecarContainerSpec = Maps.newHashMap(
					"name", "sidecar", 
					"image", "1dev/k8s-helper-" + baselineOsInfo.getHelperImageSuffix() + ":" + helperImageVersion, 
					"command", Lists.newArrayList("java"), 
					"args", sidecarArgs, 
					"env", envs, 
					"volumeMounts", volumeMounts);
			
			Map<Object, Object> initContainerSpec = Maps.newHashMap(
					"name", "init", 
					"image", "1dev/k8s-helper-" + baselineOsInfo.getHelperImageSuffix() + ":" + helperImageVersion, 
					"command", Lists.newArrayList("java"), 
					"args", initArgs,
					"env", envs,
					"volumeMounts", volumeMounts);
			
			podSpec.put("containers", Lists.<Object>newArrayList(mainContainerSpec, sidecarContainerSpec));
			podSpec.put("initContainers", Lists.<Object>newArrayList(initContainerSpec));

			Map<Object, Object> affinity = getAffinity(jobContext);
			if (affinity != null) 
				podSpec.put("affinity", affinity);
			
			if (imagePullSecretName != null)
				podSpec.put("imagePullSecrets", Lists.<Object>newArrayList(Maps.newLinkedHashMap("name", imagePullSecretName)));
			if (getServiceAccount() != null)
				podSpec.put("serviceAccountName", getServiceAccount());
			podSpec.put("restartPolicy", "Never");		
			
			if (!getNodeSelector().isEmpty())
				podSpec.put("nodeSelector", toMap(getNodeSelector()));
			
			Map<Object, Object> ciHomeVolume = Maps.newLinkedHashMap(
					"name", "ci-home", 
					"emptyDir", Maps.newLinkedHashMap());
			Map<Object, Object> cacheHomeVolume = Maps.newLinkedHashMap(
					"name", "cache-home", 
					"hostPath", Maps.newLinkedHashMap(
							"path", baselineOsInfo.getCacheHome(), 
							"type", "DirectoryOrCreate"));
			List<Object> volumes = Lists.<Object>newArrayList(ciHomeVolume, cacheHomeVolume);
			if (trustCertsConfigMapName != null) {
				Map<Object, Object> trustCertsHomeVolume = Maps.newLinkedHashMap(
						"name", "trust-certs-home", 
						"configMap", Maps.newLinkedHashMap(
								"name", trustCertsConfigMapName));			
				volumes.add(trustCertsHomeVolume);
			}
			if (dockerSock != null) {
				Map<Object, Object> dockerSockVolume = Maps.newLinkedHashMap(
						"name", "docker-sock", 
						"hostPath", Maps.newLinkedHashMap(
								"path", dockerSock, 
								"type", "File"));
				volumes.add(dockerSockVolume);
			}
			podSpec.put("volumes", volumes);

			String podName = "job";
			
			Map<Object, Object> podDef = Maps.newLinkedHashMap(
					"apiVersion", "v1", 
					"kind", "Pod", 
					"metadata", Maps.newLinkedHashMap(
							"name", podName, 
							"namespace", namespace), 
					"spec", podSpec);
			
			createResource(podDef, Sets.newHashSet(), jobLogger);
			String podFQN = namespace + "/" + podName;
			jobLogger.log("Preparing job environment...");
			
			logger.debug("Checking error events (pod: {})...", podFQN);
			// Some errors only reported via events
			checkEventError(namespace, podName, jobLogger);
			
			logger.debug("Waiting for init container to start (pod: {})...", podFQN);
			watchPod(namespace, podName, new StatusChecker() {

				@Override
				public StopWatch check(JsonNode statusNode) {
					JsonNode initContainerStatusesNode = statusNode.get("initContainerStatuses");
					if (isContainerStarted(initContainerStatusesNode, "init"))
						return new StopWatch(null);
					else
						return null;
				}
				
			}, jobLogger);
			
			if (jobContext != null)
				jobContext.notifyJobRunning();
			
			AtomicReference<String> nodeNameRef = new AtomicReference<>(null);
			
			kubectl.clearArgs();
			kubectl.addArgs("get", "pod", podName, "-n", namespace, "-o", "jsonpath={.spec.nodeName}");
			kubectl.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					nodeNameRef.set(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log("Kubernetes: " + line);
				}
				
			}).checkReturnCode();
			
			String nodeName = Preconditions.checkNotNull(nodeNameRef.get());
			jobLogger.log("Running job pod on node " + nodeName + "...");
			
			logger.debug("Collecting init container log (pod: {})...", podFQN);
			collectContainerLog(namespace, podName, "init", KubernetesHelper.LOG_END_MESSAGE, jobLogger);
			
			if (jobContext != null && isCreateCacheLabels()) 
				updateCacheLabels(nodeName, jobContext, jobLogger);
			
			logger.debug("Waiting for main container to start (pod: {})...", podFQN);
			watchPod(namespace, podName, new StatusChecker() {

				@Override
				public StopWatch check(JsonNode statusNode) {
					JsonNode initContainerStatusesNode = statusNode.get("initContainerStatuses");
					String errorMessage = getContainerError(initContainerStatusesNode, "init");
					if (errorMessage != null)
						return new StopWatch(new OneException("Error executing init logic: " + errorMessage));
					
					JsonNode containerStatusesNode = statusNode.get("containerStatuses");
					if (isContainerStarted(containerStatusesNode, "main")) 
						return new StopWatch(null);
					else
						return null;
				}
				
			}, jobLogger);
			
			logger.debug("Collecting main container log (pod: {})...", podFQN);
			collectContainerLog(namespace, podName, "main", KubernetesHelper.LOG_END_MESSAGE, jobLogger);
			
			logger.debug("Waiting for sidecar container to start (pod: {})...", podFQN);
			watchPod(namespace, podName, new StatusChecker() {

				@Override
				public StopWatch check(JsonNode statusNode) {
					JsonNode containerStatusesNode = statusNode.get("containerStatuses");
					if (isContainerStarted(containerStatusesNode, "sidecar"))
						return new StopWatch(null);
					else
						return null;
				}
				
			}, jobLogger);
			
			logger.debug("Collecting sidecar container log (pod: {})...", podFQN);
			collectContainerLog(namespace, podName, "sidecar", KubernetesHelper.LOG_END_MESSAGE, jobLogger);
			
			logger.debug("Checking execution result (pod: {})...", podFQN);
			watchPod(namespace, podName, new StatusChecker() {

				@Override
				public StopWatch check(JsonNode statusNode) {
					JsonNode containerStatusesNode = statusNode.get("containerStatuses");
					String errorMessage = getContainerError(containerStatusesNode, "main");
					if (errorMessage != null) {
						return new StopWatch(new OneException(errorMessage));
					} else {
						errorMessage = getContainerError(containerStatusesNode, "sidecar");
						if (errorMessage != null)
							return new StopWatch(new OneException("Error executing sidecar logic: " + errorMessage));
						else if (isContainerStopped(containerStatusesNode, "sidecar"))
							return new StopWatch(null);
						else
							return null;
					}
				}
				
			}, jobLogger);
			
			if (jobContext != null && isCreateCacheLabels()) 
				updateCacheLabels(nodeName, jobContext, jobLogger);
		} finally {
			deleteNamespace(namespace, jobLogger);
		}
	}
	
	@Nullable
	private String getContainerError(@Nullable JsonNode containerStatusesNode, String containerName) {
		if (containerStatusesNode != null) {
			for (JsonNode containerStatusNode: containerStatusesNode) {
				JsonNode stateNode = containerStatusNode.get("state");
				if (containerStatusNode.get("name").asText().equals(containerName)) {
					JsonNode terminatedNode = stateNode.get("terminated");
					if (terminatedNode != null) {
						String reason;
						JsonNode reasonNode = terminatedNode.get("reason");
						if (reasonNode != null)
							reason = reasonNode.asText();
						else
							reason = "terminated for unknown reason";
						
						if (!reason.equals("Completed")) {
							JsonNode messageNode = terminatedNode.get("message");
							if (messageNode != null) {
								return messageNode.asText();
							} else {
								JsonNode exitCodeNode = terminatedNode.get("exitCode");
								if (exitCodeNode != null && exitCodeNode.asInt() != 0)
									return "exit code: " + exitCodeNode.asText();
								else
									return reason;
							}
						}
					}
					break;
				}
			}
		}
		return null;
	}
	
	private boolean isContainerStarted(@Nullable JsonNode containerStatusesNode, String containerName) {
		if (containerStatusesNode != null) {
			for (JsonNode containerStatusNode: containerStatusesNode) {
				if (containerStatusNode.get("name").asText().equals(containerName)) {
					JsonNode stateNode = containerStatusNode.get("state");
					if (stateNode.get("running") != null || stateNode.get("terminated") != null)
						return true;
					break;
				}
			}
		}
		return false;
	}
	
	private boolean isContainerStopped(@Nullable JsonNode containerStatusesNode, String containerName) {
		if (containerStatusesNode != null) {
			for (JsonNode containerStatusNode: containerStatusesNode) {
				if (containerStatusNode.get("name").asText().equals(containerName)) {
					JsonNode stateNode = containerStatusNode.get("state");
					if (stateNode.get("terminated") != null)
						return true;
					break;
				}
			}
		}
		return false;
	}
	
	private void updateCacheLabels(String nodeName, JobContext jobContext, JobLogger jobLogger) {
		jobLogger.log("Updating cache labels on node...");
		
		Commandline kubectl = newKubeCtl();
		kubectl.clearArgs();
		StringBuilder nodeJson = new StringBuilder();
		kubectl.addArgs("get", "node", nodeName, "-o", "json");
		kubectl.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("{")) 
					nodeJson.append("{").append("\n");
				else if (line.startsWith("}")) 
					nodeJson.append("}");
				else 
					nodeJson.append(line).append("\n");
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log("Kubernetes: " + line);
			}
			
		}).checkReturnCode();

		JsonNode nodeNode;
		logger.trace("Node json:\n" + nodeJson.toString());
		try {
			nodeNode = OneDev.getInstance(ObjectMapper.class).readTree(nodeJson.toString());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		List<String> labelUpdates = new ArrayList<>();

		Iterator<Map.Entry<String, JsonNode>> it = nodeNode.get("metadata").get("labels").fields();
		while (it.hasNext()) {
			Map.Entry<String, JsonNode> entry = it.next();
			if (entry.getKey().startsWith(CACHE_LABEL_PREFIX)) {
				String cacheKey = entry.getKey().substring(CACHE_LABEL_PREFIX.length());
				int labelValue = entry.getValue().asInt();
				Integer count = jobContext.getCacheCounts().remove(cacheKey);
				if (count == null)
					labelUpdates.add(entry.getKey() + "-");
				else if (count != labelValue)
					labelUpdates.add(entry.getKey() + "=" + count);
			}
		}
		
		for (Map.Entry<String, Integer> entry: jobContext.getCacheCounts().entrySet())
			labelUpdates.add(CACHE_LABEL_PREFIX + entry.getKey() + "=" + entry.getValue());
		
		jobContext.getCacheCounts().clear();
		
		for (List<String> partition: Lists.partition(labelUpdates, LABEL_UPDATE_BATCH)) {
			kubectl.clearArgs();
			kubectl.addArgs("label", "node", nodeName, "--overwrite");
			for (String labelUpdate: partition) 
				kubectl.addArgs(labelUpdate);
			AtomicBoolean labelNotFound = new AtomicBoolean(false);
			ExecuteResult result = kubectl.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.debug(line);
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.startsWith("label") && line.endsWith("not found."))
						labelNotFound.set(true);
					jobLogger.log("Kubernetes: " + line);
				}
				
			});
			if (!labelNotFound.get())
				result.checkReturnCode();
		}
	}
	
	private void watchPod(String namespace, String podName, StatusChecker statusChecker, JobLogger jobLogger) {
		Commandline kubectl = newKubeCtl();
		
		ObjectMapper mapper = OneDev.getInstance(ObjectMapper.class);
		
		AtomicReference<StopWatch> stopWatchRef = new AtomicReference<>(null); 
		
		StringBuilder json = new StringBuilder();
		kubectl.addArgs("get", "pod", podName, "-n", namespace, "--watch", "-o", "json");
		
		Thread thread = Thread.currentThread();
		try {
			kubectl.execute(new LineConsumer() {
	
				@Override
				public void consume(String line) {
					if (line.startsWith("{")) {
						json.append("{").append("\n");
					} else if (line.startsWith("}")) {
						json.append("}");
						logger.trace("Pod watching output:\n" + json.toString());
						try {
							process(mapper.readTree(json.toString()));
						} catch (Exception e) {
							logger.error("Error processing pod watching output", e);
						}
						json.setLength(0);
					} else {
						json.append(line).append("\n");
					}
				}

				private void process(JsonNode podNode) {
					String errorMessage = null;
					JsonNode statusNode = podNode.get("status");
					JsonNode conditionsNode = statusNode.get("conditions");
					if (conditionsNode != null) {
						for (JsonNode conditionNode: conditionsNode) {
							if (conditionNode.get("type").asText().equals("PodScheduled") 
									&& conditionNode.get("status").asText().equals("False")
									&& conditionNode.get("reason").asText().equals("Unschedulable")) {
								jobLogger.log("Kubernetes: " + conditionNode.get("message").asText());
							}
						}
					}
					
					Collection<JsonNode> containerStatusNodes = new ArrayList<>();
					JsonNode initContainerStatusesNode = statusNode.get("initContainerStatuses");
					if (initContainerStatusesNode != null) {
						for (JsonNode containerStatusNode: initContainerStatusesNode)
							containerStatusNodes.add(containerStatusNode);
					}
					JsonNode containerStatusesNode = statusNode.get("containerStatuses");
					if (containerStatusesNode != null) {
						for (JsonNode containerStatusNode: containerStatusesNode)
							containerStatusNodes.add(containerStatusNode);
					}
					
					for (JsonNode containerStatusNode: containerStatusNodes) {
						JsonNode stateNode = containerStatusNode.get("state");
						JsonNode waitingNode = stateNode.get("waiting");
						if (waitingNode != null) {
							String reason = waitingNode.get("reason").asText();
							if (reason.equals("ErrImagePull") || reason.equals("InvalidImageName") 
									|| reason.equals("ImageInspectError") || reason.equals("ErrImageNeverPull")
									|| reason.equals("RegistryUnavailable")) {
								JsonNode messageNode = waitingNode.get("message");
								if (messageNode != null)
									errorMessage = messageNode.asText();
								else
									errorMessage = reason;
								break;
							}
						} 
					}
					if (errorMessage != null) 
						stopWatchRef.set(new StopWatch(new OneException(errorMessage)));
					else 
						stopWatchRef.set(statusChecker.check(statusNode));
					if (stopWatchRef.get() != null) 
						thread.interrupt();
				}
				
			}, new LineConsumer() {
	
				@Override
				public void consume(String line) {
					jobLogger.log("Kubernetes: " + line);
				}
				
			}).checkReturnCode();
			
			throw new OneException("Unexpected end of pod watching");
		} catch (Exception e) {
			StopWatch stopWatch = stopWatchRef.get();
			if (stopWatch != null) {
				if (stopWatch.getException() != null)
					throw stopWatch.getException();
			} else { 
				throw ExceptionUtils.unchecked(e);
			}
		}		
	}
	
	private void checkEventError(String namespace, String podName, JobLogger jobLogger) {
		Commandline kubectl = newKubeCtl();
		
		ObjectMapper mapper = OneDev.getInstance(ObjectMapper.class);
		
		StringBuilder json = new StringBuilder();
		kubectl.addArgs("get", "event", "-n", namespace, "--field-selector", 
				"involvedObject.kind=Pod,involvedObject.name=" + podName, "--watch", 
				"-o", "json");
		Thread thread = Thread.currentThread();
		AtomicReference<StopWatch> stopWatchRef = new AtomicReference<>(null);
		try {
			kubectl.execute(new LineConsumer() {
	
				@Override
				public void consume(String line) {
					if (line.startsWith("{")) {
						json.append("{").append("\n");
					} else if (line.startsWith("}")) {
						json.append("}");
						logger.trace("Watching event:\n" + json.toString());
						try {
							JsonNode eventNode = mapper.readTree(json.toString()); 
							String type = eventNode.get("type").asText();
							String reason = eventNode.get("reason").asText();
							JsonNode messageNode = eventNode.get("message");
							String message = messageNode!=null? messageNode.asText(): reason;
							if (type.equals("Warning")) {
								if (reason.equals("FailedScheduling"))
									jobLogger.log("Kubernetes: " + message);
								else
									stopWatchRef.set(new StopWatch(new OneException(message)));
							} else if (type.equals("Normal") && reason.equals("Started")) {
								stopWatchRef.set(new StopWatch(null));
							}
							if (stopWatchRef.get() != null)
								thread.interrupt();
						} catch (Exception e) {
							logger.error("Error processing event watching record", e);
						}
						json.setLength(0);
					} else {
						json.append(line).append("\n");
					}
				}
				
			}, new LineConsumer() {
	
				@Override
				public void consume(String line) {
					jobLogger.log("Kubernetes: " + line);
				}
				
			}).checkReturnCode();
			
			throw new OneException("Unexpected end of event watching");
		} catch (Exception e) {
			StopWatch stopWatch = stopWatchRef.get();
			if (stopWatch != null) {
				if (stopWatch.getException() != null)
					throw stopWatch.getException();
			} else { 
				throw ExceptionUtils.unchecked(e);
			}
		}		
	}
	
	private void collectContainerLog(String namespace, String podName, String containerName, 
			@Nullable String logEndMessage, JobLogger jobLogger) {
		if (logEndMessage != null) {
			AtomicReference<Instant> lastInstantRef = new AtomicReference<>(null);
			AtomicBoolean endOfLogSeenRef = new AtomicBoolean(false);
			
			while (true) {
				Commandline kubectl = newKubeCtl();
				kubectl.addArgs("logs", podName, "-c", containerName, "-n", namespace, "--follow", "--timestamps=true");
				if (lastInstantRef.get() != null)
					kubectl.addArgs("--since-time=" + DateTimeFormatter.ISO_INSTANT.format(lastInstantRef.get()));
				
				LineConsumer logConsumer = new LineConsumer() {

					@Override
					public void consume(String line) {
						if (line.contains("rpc error:") && line.contains("No such container:") 
								|| line.contains("Unable to retrieve container logs for")) { 
							logger.debug(line);
						} else if (line.contains(logEndMessage)) {
							endOfLogSeenRef.set(true);
						} else if (line.contains(" ")) {
							String timestamp = StringUtils.substringBefore(line, " ");
							try {
								Instant instant = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(timestamp));
								if (lastInstantRef.get() == null || lastInstantRef.get().isBefore(instant))
									lastInstantRef.set(instant);
								jobLogger.log(StringUtils.substringAfter(line, " "));
							} catch (DateTimeParseException e) {
								jobLogger.log(line);
							}
						} else {
							jobLogger.log(line);
						}
					}
					
				};
				
				kubectl.execute(logConsumer, logConsumer).checkReturnCode();
				
				if (endOfLogSeenRef.get()) {
					break;
				} else {
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						throw new RuntimeException(e);
					}
				}
			}
		} else {
			Commandline kubectl = newKubeCtl();
			kubectl.addArgs("logs", podName, "-c", containerName, "-n", namespace, "--follow");
			LineConsumer logConsumer = new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("rpc error:") && line.contains("No such container:") 
							|| line.contains("Unable to retrieve container logs for")) { 
						logger.debug(line);
					} else {
						jobLogger.log(line);
					}
				}
				
			};
			
			kubectl.execute(logConsumer, logConsumer).checkReturnCode();
		}
	}
	
	private static interface StatusChecker {
		
		StopWatch check(JsonNode statusNode);
		
	}
	
	private static class StopWatch {
		
		private final RuntimeException exception;
		
		public StopWatch(@Nullable RuntimeException exception) {
			this.exception = exception;
		}
		
		@Nullable
		public RuntimeException getException() {
			return exception;
		}
		
	}
	
	@Editable(name="Specify a Docker Image to Test Against")
	public static class TestData implements Serializable {

		private static final long serialVersionUID = 1L;

		private String dockerImage;

		@Editable
		@OmitName
		@NotEmpty
		public String getDockerImage() {
			return dockerImage;
		}

		public void setDockerImage(String dockerImage) {
			this.dockerImage = dockerImage;
		}
		
	}

	private static class OsInfo {
		
		private static final Map<String, Integer> WINDOWS_VERSIONS = new LinkedHashMap<>();
		
		static {
			// update this according to 
			// https://docs.microsoft.com/en-us/virtualization/windowscontainers/deploy-containers/version-compatibility
			WINDOWS_VERSIONS.put(".18362.", 1903);
			WINDOWS_VERSIONS.put(".17763.", 1809);
			WINDOWS_VERSIONS.put(".17134.", 1803); 
			WINDOWS_VERSIONS.put(".16299.", 1709); 
			WINDOWS_VERSIONS.put(".14393.", 1607);
		}
		
		private final String osName;
		
		private final String kernelVersion;
		
		public OsInfo(String osName, String kernelVersion) {
			this.osName = osName;
			this.kernelVersion = kernelVersion;
		}

		public boolean isLinux() {
			return osName.equalsIgnoreCase("linux");
		}
		
		public boolean isWindows() {
			return osName.equalsIgnoreCase("windows");
		}
		
		public String getCacheHome() {
			if (osName.equalsIgnoreCase("linux"))
				return "/var/cache/onedev-ci"; 
			else
				return "C:\\ProgramData\\onedev-ci\\cache";
		}
		
		public static OsInfo getBaseline(Collection<OsInfo> osInfos) {
			if (osInfos.iterator().next().isLinux()) {
				for (OsInfo osInfo: osInfos) {
					if (!osInfo.isLinux())
						throw new OneException("Linux and non-linux nodes should not be included in same executor");
				}
				return osInfos.iterator().next();
			} else if (osInfos.iterator().next().isWindows()) {
				OsInfo baseline = null;
				for (OsInfo osInfo: osInfos) {
					if (!osInfo.isWindows())
						throw new OneException("Windows and non-windows nodes should not be included in same executor");
					if (baseline == null || baseline.getWindowsVersion() > osInfo.getWindowsVersion())
						baseline = osInfo;
				}
				return baseline;
			} else {
				throw new OneException("Either Windows or Linux nodes can be included in an executor");
			}
		}
		
		public int getWindowsVersion() {
			Preconditions.checkState(isWindows());
			for (Map.Entry<String, Integer> entry: WINDOWS_VERSIONS.entrySet()) {
				if (kernelVersion.contains(entry.getKey()))
					return entry.getValue();
			}
			throw new RuntimeException("Unsupported windows kernel version: " + kernelVersion);
		}
		
		public String getHelperImageSuffix() {
			if (isLinux())  
				return "linux";
			else 
				return "windows-" + getWindowsVersion();
		}
		
	}
	
}