package org.jmt.mcmt.coremod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.launchwrapper.IClassTransformer;

public class MCMTClassTransformer implements IClassTransformer
{
    private static final Logger LOG = LogManager.getLogger("MCMT-Core");
    private static boolean logged = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass)
    {
        if (!logged)
        {
            logged = true;
            LOG.info("MCMT coremod transformer active");
        }
        return basicClass;
    }
}
