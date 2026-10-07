package org.jmt.mcmt.coremod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraft.launchwrapper.IClassTransformer;

public class MCMTClassTransformer implements IClassTransformer
{
    private static final Logger LOG = LogManager.getLogger("MCMT-Core");
    private static final String HOOK = "org/jmt/mcmt/asmdest/ASMHookTerminator";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass)
    {
        if (transformedName.equals("net.minecraft.server.MinecraftServer"))
        {
            try
            {
                byte[] out = patchMinecraftServer(basicClass);
                LOG.info("MCMT coremod transformer active");
                return out;
            }
            catch (Exception e)
            {
                LOG.error("MCMT failed to patch MinecraftServer, running without world parallelism", e);
            }
        }
        if (transformedName.equals("net.minecraft.world.World"))
        {
            try
            {
                return patchWorld(basicClass);
            }
            catch (Exception e)
            {
                LOG.error("MCMT failed to patch World, running without entity parallelism", e);
            }
        }
        return basicClass;
    }

    private byte[] patchWorld(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        MethodNode m = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.desc.equals("()V")
                    && (mn.name.equals("updateEntities") || mn.name.equals("func_72939_s")))
            {
                m = mn;
                break;
            }
        }
        if (m == null)
        {
            LOG.error("updateEntities not found in World");
            return in;
        }
        InsnList ins = m.instructions;
        int n = 0;
        for (AbstractInsnNode ain = ins.getFirst(); ain != null; ain = ain.getNext())
        {
            if (!(ain instanceof MethodInsnNode))
                continue;
            MethodInsnNode mi = (MethodInsnNode) ain;
            if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && mi.owner.equals("net/minecraft/entity/Entity")
                    && (mi.name.equals("onUpdate") || mi.name.equals("func_70071_h_"))
                    && mi.desc.equals("()V"))
            {
                // stack holds the entity; append this-world and dispatch
                InsnList rep = new InsnList();
                rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "callEntityTick",
                        "(Lnet/minecraft/entity/Entity;Lnet/minecraft/world/World;)V", false));
                ins.insertBefore(mi, rep);
                ins.remove(mi);
                n++;
            }
        }
        if (n == 0)
            LOG.error("Entity.onUpdate call site not found in updateEntities");
        else
            LOG.info("Patched World: " + n + " entity tick dispatch site(s)");

        // TE loop: ((ITickable)te).update() -> callTileEntityTick(te, world)
        int t = 0;
        for (AbstractInsnNode ain = ins.getFirst(); ain != null; )
        {
            AbstractInsnNode next = ain.getNext();
            if (ain instanceof MethodInsnNode)
            {
                MethodInsnNode mi = (MethodInsnNode) ain;
                if (mi.getOpcode() == Opcodes.INVOKEINTERFACE
                        && mi.owner.equals("net/minecraft/util/ITickable")
                        && mi.name.equals("update") && mi.desc.equals("()V"))
                {
                    AbstractInsnNode prev = mi.getPrevious();
                    if (prev instanceof TypeInsnNode && ((TypeInsnNode) prev).desc.equals("net/minecraft/util/ITickable"))
                    {
                        ins.remove(prev);
                        InsnList rep = new InsnList();
                        rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "callTileEntityTick",
                                "(Lnet/minecraft/tileentity/TileEntity;Lnet/minecraft/world/World;)V", false));
                        ins.insertBefore(mi, rep);
                        ins.remove(mi);
                        t++;
                    }
                }
            }
            ain = next;
        }
        if (t == 0)
            LOG.error("ITickable.update call site not found in updateEntities");
        else
            LOG.info("Patched World: " + t + " TE tick dispatch site(s)");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private byte[] patchMinecraftServer(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        MethodNode m = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.desc.equals("()V")
                    && (mn.name.equals("updateTimeLightAndEntities") || mn.name.equals("func_71190_q")))
            {
                m = mn;
                break;
            }
        }
        if (m == null)
        {
            LOG.error("updateTimeLightAndEntities not found in MinecraftServer");
            return in;
        }
        InsnList ins = m.instructions;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "preTick",
                "(Lnet/minecraft/server/MinecraftServer;)V", false));
        ins.insert(pre);

        int n = 0;
        for (AbstractInsnNode ain = ins.getFirst(); ain != null; ain = ain.getNext())
        {
            if (!(ain instanceof MethodInsnNode))
                continue;
            MethodInsnNode mi = (MethodInsnNode) ain;
            if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && mi.owner.equals("net/minecraft/world/WorldServer")
                    && (mi.name.equals("tick") || mi.name.equals("func_73028_a"))
                    && mi.desc.equals("()V"))
            {
                // stack currently holds the WorldServer receiver; add the
                // MinecraftServer and hand both to the hook terminator
                InsnList rep = new InsnList();
                rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
                rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "callTick",
                        "(Lnet/minecraft/world/WorldServer;Lnet/minecraft/server/MinecraftServer;)V", false));
                ins.insertBefore(mi, rep);
                ins.remove(mi);
                n++;
            }
        }
        if (n == 0)
            LOG.error("WorldServer.tick call site not found in updateTimeLightAndEntities");
        else
            LOG.info("Patched MinecraftServer: preTick + " + n + " world tick dispatch site(s)");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
