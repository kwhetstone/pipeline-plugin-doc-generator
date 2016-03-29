package org.jenkinsci.pipeline_steps_doc_generator;


import hudson.init.InitMilestone;
import hudson.init.InitStrategy;
import hudson.security.ACL;
import jenkins.InitReactorRunner;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.structs.describable.ArrayType;
import org.jenkinsci.plugins.structs.describable.AtomicType;
import org.jenkinsci.plugins.structs.describable.DescribableModel;
import org.jenkinsci.plugins.structs.describable.DescribableParameter;
import org.jenkinsci.plugins.structs.describable.EnumType;
import org.jenkinsci.plugins.structs.describable.ErrorType;
import org.jenkinsci.plugins.structs.describable.HeterogeneousObjectType;
import org.jenkinsci.plugins.structs.describable.HomogeneousObjectType;
import org.jenkinsci.plugins.structs.describable.ParameterType;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jvnet.hudson.reactor.Reactor;
import org.jvnet.hudson.reactor.ReactorException;
import org.jvnet.hudson.reactor.Task;
import org.jvnet.hudson.reactor.TaskBuilder;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeSet;

/**
 * Process and find all the Pipeline steps definied in Jenkins plugins.
 */
public class PipelineStepExtractor {
    @Option(name="-homeDir",usage="Root directory of the plugin folder.  This serves as the root directory of the PluginManager.")
    public String homeDir = null;

    /**
     * Keeps track of nested {@link DescribableModel#getType()} to avoid recursion.
     */
    private Stack<Class> nesting = new Stack<>();

    public static void main(String[] args){
        PipelineStepExtractor pse = new PipelineStepExtractor();
        try{
            CmdLineParser p = new CmdLineParser(pse);
            p.parseArgument(args);
        } catch(Exception ex){
            System.out.println("There was an error with parsing the commands, defaulting to the home directory.");
        }
        pse.generateAscii(pse.findSteps());
        System.out.println("COMPLETED FILE PROCESSING");
    }

    public Map<String, Map<String, List<StepDescriptor>>> findSteps(){
        Map<String, Map<String, List<StepDescriptor>>> completeListing = new HashMap<String, Map<String, List<StepDescriptor>>>();
        try {
            //setup
            HyperLocalPluginManger spm;
            if(homeDir == null){
                spm = new HyperLocalPluginManger();
            } else {
                spm = new HyperLocalPluginManger(homeDir);
            }
            InitStrategy initStrategy = new InitStrategy();
            executeReactor(initStrategy, spm.diagramPlugins(initStrategy));
            List<StepDescriptor> steps = spm.getPluginStrategy().findComponents(StepDescriptor.class, spm.uberPlusClassLoader);
            Map<String, String> stepsToPlugin = spm.uberPlusClassLoader.getByPlugin();

            //what does restructuring look like?
            Map<String, List<StepDescriptor>> required = processSteps(false, steps, stepsToPlugin);
            Map<String, List<StepDescriptor>> optional = processSteps(true, steps, stepsToPlugin);

            completeListing.put("Steps", required);
            completeListing.put("Advanced/Deprecated Steps", optional);
            //deal with variables later
        } catch (Exception ex){
            //log exception, job essentially fails
        }

        return completeListing;
    }

    private Map<String, List<StepDescriptor>> processSteps(boolean optional, List<StepDescriptor> steps, Map<String, String> stepsToPlugin){
        Map<String, List<StepDescriptor>> required = new HashMap<String, List<StepDescriptor>>();
        for (StepDescriptor d : getStepDescriptors(optional, steps)) {
            String pluginName = stepsToPlugin.get(d.getClass().getName()).trim();
            List<StepDescriptor> allSteps = required.get(pluginName);
            if(allSteps == null){
                allSteps = new ArrayList<StepDescriptor>();
            }
            allSteps.add(d);
            required.put(pluginName, allSteps);
        }
        return required;
    }

    /**
     * Executes a reactor.
     *
     * @param is
     *      If non-null, this can be consulted for ignoring some tasks. Only used during the initialization of Jenkins.
     */
    private void executeReactor(final InitStrategy is, TaskBuilder... builders) throws IOException, InterruptedException, ReactorException {
        Reactor reactor = new Reactor(builders) {
            /**
             * Sets the thread name to the task for better diagnostics.
             */
            @Override
            protected void runTask(Task task) throws Exception {
                if (is!=null && is.skipInitTask(task))  return;

                ACL.impersonate(ACL.SYSTEM); // full access in the initialization thread
                String taskName = task.getDisplayName();

                Thread t = Thread.currentThread();
                String name = t.getName();
                if (taskName !=null)
                    t.setName(taskName);
                try {
                    long start = System.currentTimeMillis();
                    super.runTask(task);
                } finally {
                    t.setName(name);
                    SecurityContextHolder.clearContext();
                }
            }
        };

        new InitReactorRunner() {
            @Override
            protected void onInitMilestoneAttained(InitMilestone milestone) {
                System.out.println("init milestone attained " + milestone);
            }
        }.run(reactor);
    }

    public Collection<? extends StepDescriptor> getStepDescriptors(boolean advanced, List<StepDescriptor> all) {
        TreeSet<StepDescriptor> t = new TreeSet<StepDescriptor>(new StepDescriptorComparator());
        for (StepDescriptor d : all) {
            if (d.isAdvanced() == advanced) {
                t.add(d);
            }
        }
        return t;
    }

    private static class StepDescriptorComparator implements Comparator<StepDescriptor>, Serializable {
        @Override
        public int compare(StepDescriptor o1, StepDescriptor o2) {
            return o1.getFunctionName().compareTo(o2.getFunctionName());
        }
        private static final long serialVersionUID = 1L;
    }

    public void generateAscii(Map<String, Map<String, List<StepDescriptor>>> allSteps){
        File allAscii = new File("allAscii");
        allAscii.mkdirs();
        String allAsciiPath = allAscii.getAbsolutePath();
        for(String type : allSteps.keySet()){
            Map<String, List<StepDescriptor>> byPlugin = allSteps.get(type);
            for(String plugin : byPlugin.keySet()){
                System.out.println("processing " + plugin);
                String whole9yards = ""; //in reality, this would be the perfect place to generate the elastic search header
                for(StepDescriptor sd : byPlugin.get(plugin)){
                    whole9yards += generateStepHelp(sd);
                }
                try{
                    FileUtils.writeStringToFile(new File(allAsciiPath, plugin + ".ad"), whole9yards, StandardCharsets.UTF_8);
                } catch (Exception ex){
                    System.out.println("Error generating plugin file for " + plugin + ".  Skip.");
                    //continue to next plugin
                }
            }
        }
    }
    /******************************************************************************************************************/
    /** Asciidoc conversion functions. **/
    private String header(int depth){
        return String.join("", Collections.nCopies(depth, "="));
    }

    private String listDepth(int depth){
        return String.join("", Collections.nCopies(depth+1, ":"));
    }

    private String stripDiv(String tagged){
        return tagged.replaceAll("<(.|\n)*?>", "").trim();
    }

    private String helpify(String help){
        return "====\n"+stripDiv(help)+"\n====\n";
    }

    private String describeType(ParameterType type, int headerLevel) throws Exception {
        String typeInfo = "";
        int nextHeaderLevel = Math.min(6, headerLevel + 1);
        if (type instanceof AtomicType) {
            typeInfo += "*Type:* "+type+"\n";
        } else if (type instanceof EnumType) {
            typeInfo += "*Values:*\n";
            for (String v : ((EnumType) type).getValues()) {
                typeInfo += "* +"+v+"+\n";
            }
        } else if (type instanceof ArrayType) {
            typeInfo += "*Array/List*\n";
            typeInfo +=  describeType(((ArrayType) type).getElementType(), headerLevel);
        } else if (type instanceof HomogeneousObjectType) {
            typeInfo += "Nested Object\n";
            typeInfo += generateHelp(((HomogeneousObjectType) type).getSchemaType(), nextHeaderLevel);
        } else if (type instanceof HeterogeneousObjectType) {
            typeInfo += "Nested Choice of Objects\n";
            for (Map.Entry<String, DescribableModel> entry : ((HeterogeneousObjectType) type).getTypes().entrySet()) {
                typeInfo += "+"+ DescribableModel.CLAZZ+": '"+entry.getKey()+"'+\n";
                typeInfo += generateHelp(entry.getValue(), nextHeaderLevel);
            }
        } else if (type instanceof ErrorType) {
            Exception x = ((ErrorType) type).getError();
            typeInfo += "+"+x+"+\n";
        } else {
            assert false: type;
        }
        return typeInfo;
    }

    private String generateAttrHelp(DescribableParameter param, int headerLevel) throws Exception {
        String attrHelp = "";
        String help = param.getHelp();
        if (help != null && !help.equals("")) {
            //attrHelp += this.helpify(help)
            attrHelp += stripDiv(help) + "\n";
        }
        ParameterType type = param.getType();
        attrHelp += describeType(type, headerLevel);
        return attrHelp;
    }

    private String generateHelp(DescribableModel<?> schema, int headerLevel) throws Exception {
        if (nesting.contains(schema.getType()))
            return "";  // if we are recursing, cut the search
        nesting.push(schema.getType());
        try {
            String total = "";
            String help = schema.getHelp();
            if (help != null && !help.equals("")) {
                total += this.helpify(help);
            }
            //dl(class:'help-list mandatory')
            for (DescribableParameter p : schema.getParameters()) {
                if (p.isRequired()) {
                    total += "+" + p.getName() + "+" + listDepth(headerLevel) + "\n+\n";
                    total += generateAttrHelp(p, headerLevel);
                    total += "\n\n";
                }
            }
            //dl(class:'help-list optional'){
            for (DescribableParameter p : schema.getParameters()) {
                if (!p.isRequired()) {
                    //total += "${this.header(headerLevel)} `${attr}` (optional)\n"
                    total += "+" + p.getName() + "+ (optional)" + listDepth(headerLevel) + "\n+\n";
                    total += generateAttrHelp(p, headerLevel);
                    total += "\n\n";
                }
            }
            return total;
        } finally {
            nesting.pop();
        }
    }

    public String generateStepHelp(StepDescriptor d){
        String mkDesc = header(2)+ "+"+d.getFunctionName()+"+: "+d.getDisplayName()+"\n";
        try{
            mkDesc += generateHelp(new DescribableModel(d.clazz), 1) +"\n";
        } catch(Exception ex){
            mkDesc += "+"+ex+"+\n\n";
        } catch(Error err){
            mkDesc += "+"+err+"+\n\n";
        }
        return mkDesc;
    }
}
