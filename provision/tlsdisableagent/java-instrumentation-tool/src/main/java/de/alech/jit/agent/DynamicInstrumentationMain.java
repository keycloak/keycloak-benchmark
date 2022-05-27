package de.alech.jit.agent;

import com.sun.tools.attach.VirtualMachine;

public class DynamicInstrumentationMain {
    public static void main(String[] args) {
        try {
            if (args.length != 1) {
                System.err.println("Usage:\n    java [-Dagentargs=showloadedclasses] -jar agent.jar PID");
                System.err.println("Alternatively, hook at startup with\n    java -javaagent:agent.jar[=showloadedclasses] yourjar.jar");
                System.exit(1);
            }
            int pid = Integer.parseInt(args[0]);
            VirtualMachine jvm = VirtualMachine.attach(args[0]);
            String agentArgs = System.getProperty("agentargs");
            jvm.loadAgent(System.getProperty("user.dir") + "/agent.jar", agentArgs);
            jvm.detach();
        }
        catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
