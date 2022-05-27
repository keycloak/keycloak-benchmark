package de.alech.jit.agent;

import java.io.*;

/*
The location where to patch, corresponding to the CtBehavior.insertBefore() and CtBehavior.insertAfter() methods.
 */
enum WhereToPatch {
    INSERTBEFORE,
    INSERTAFTER
}

public class Hook {
    public String classToTransform;
    public String method;
    public String[] strParams;
    public WhereToPatch where;
    public String codeFileName;
    public String codePatch;

    public Hook(String definition) throws IOException {
        parseDefinition(definition);
        InputStream codeStream = Hook.class.getResourceAsStream("/hooks/" + codeFileName);
        this.codePatch = readFromInputStream(codeStream);
    }

    public Hook(String definition, String basePath) throws IOException {
        parseDefinition(definition);
        InputStream codeStream = new FileInputStream(new File(basePath + "/hooks/" + codeFileName));
        this.codePatch = readFromInputStream(codeStream);
    }

    void parseDefinition(String definition) {
        String[] parts = definition.split(",");
        this.classToTransform = parts[0];
        this.method           = parts[1];
        this.strParams        = parts[2].split(";");
        if ("".equals(parts[2])) {
            this.strParams = new String[0];
        }
        this.where = WhereToPatch.valueOf(parts[3].toUpperCase());
        this.codeFileName     = parts[4];
    }

    String readFromInputStream(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder result = new StringBuilder();
        for (String line; (line = reader.readLine()) != null; ) {
            result.append(line + "\n");
        }
        return result.toString();
    }
}
