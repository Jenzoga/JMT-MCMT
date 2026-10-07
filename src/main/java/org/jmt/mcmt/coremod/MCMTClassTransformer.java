package org.jmt.mcmt.coremod;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
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
        if (transformedName.equals("net.minecraft.world.WorldServer"))
        {
            try
            {
                return patchWorldServer(basicClass);
            }
            catch (Exception e)
            {
                LOG.error("MCMT failed to patch WorldServer, running without env parallelism", e);
            }
        }
        return basicClass;
    }

    private byte[] patchWorldServer(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        MethodNode m = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.desc.equals("()V") && (mn.name.equals("updateBlocks") || mn.name.equals("func_73084_a")))
            {
                m = mn;
                break;
            }
        }
        if (m == null)
        {
            LOG.error("updateBlocks not found in WorldServer");
            return in;
        }
        InsnList ins = m.instructions;

        // main chunk loop: iterator from getPersistentChunkIterable, hasNext -> ifeq end
        AbstractInsnNode afterGet = null;
        for (AbstractInsnNode ain = ins.getFirst(); ain != null; ain = ain.getNext())
        {
            if (ain instanceof MethodInsnNode && ((MethodInsnNode) ain).name.equals("getPersistentChunkIterable"))
            {
                afterGet = ain;
                break;
            }
        }
        if (afterGet == null)
        {
            LOG.error("getPersistentChunkIterable not found in updateBlocks");
            return in;
        }
        MethodInsnNode hasNext = null;
        for (AbstractInsnNode ain = afterGet.getNext(); ain != null; ain = ain.getNext())
        {
            if (ain instanceof MethodInsnNode && ((MethodInsnNode) ain).name.equals("hasNext"))
            {
                hasNext = (MethodInsnNode) ain;
                break;
            }
        }
        if (hasNext == null)
        {
            LOG.error("hasNext not found in updateBlocks");
            return in;
        }
        AbstractInsnNode condJump = hasNext.getNext();
        if (!(condJump instanceof JumpInsnNode))
        {
            LOG.error("loop condition jump not found in updateBlocks");
            return in;
        }
        // chunk local: next() -> checkcast Chunk -> ASTORE v
        AbstractInsnNode it = hasNext;
        int chunkLocal = -1;
        AbstractInsnNode store = null;
        while (true)
        {
            it = it.getNext();
            if (it == null)
            {
                LOG.error("iterator next not found in updateBlocks");
                return in;
            }
            if (it instanceof MethodInsnNode && ((MethodInsnNode) it).name.equals("next"))
                break;
        }
        for (AbstractInsnNode ain = it.getNext(); ain != null; ain = ain.getNext())
        {
            if (ain instanceof TypeInsnNode && ((TypeInsnNode) ain).desc.equals("net/minecraft/world/chunk/Chunk"))
            {
                AbstractInsnNode st = ain.getNext();
                if (st instanceof VarInsnNode && st.getOpcode() == Opcodes.ASTORE)
                {
                    store = st;
                    chunkLocal = ((VarInsnNode) st).var;
                }
                break;
            }
        }
        if (store == null)
        {
            LOG.error("chunk store not found in updateBlocks");
            return in;
        }
        // back edge: the first GOTO after the store whose label sits before hasNext
        AbstractInsnNode backEdge = null;
        for (AbstractInsnNode ain = store.getNext(); ain != null; ain = ain.getNext())
        {
            if (ain instanceof JumpInsnNode && ((JumpInsnNode) ain).getOpcode() == Opcodes.GOTO)
            {
                LabelNode lbl = ((JumpInsnNode) ain).label;
                for (AbstractInsnNode q = ins.getFirst(); q != null && q != hasNext; q = q.getNext())
                {
                    if (q == lbl)
                    {
                        backEdge = ain;
                        break;
                    }
                }
                if (backEdge != null)
                    break;
            }
        }
        if (backEdge == null)
        {
            LOG.error("loop back edge not found in updateBlocks");
            return in;
        }

        // extract body (store..backEdge exclusive) into WorldServer.mcmt$tickEnvChunk(Chunk)V
        java.util.List<AbstractInsnNode> body = new java.util.ArrayList<>();
        for (AbstractInsnNode ain = store.getNext(); ain != backEdge; ain = ain.getNext())
            body.add(ain);

        MethodNode extracted = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "mcmt$tickEnvChunk", "(Lnet/minecraft/world/chunk/Chunk;IZZ)V", null, null);
        for (AbstractInsnNode node : body)
            ins.remove(node);
        for (AbstractInsnNode node : body)
        {
            if (node instanceof FrameNode)
                continue; // stale locals layout; COMPUTE_FRAMES recomputes
            extracted.instructions.add(node);
        }

        // remap outer locals to parameters: chunk->1, randomTickSpeed->2, raining->3, thundering->4
        java.util.Map<Integer, Integer> remap = new java.util.HashMap<>();
        remap.put(1, 2);
        remap.put(2, 3);
        remap.put(3, 4);
        remap.put(chunkLocal, 1);
        for (AbstractInsnNode ain = extracted.instructions.getFirst(); ain != null; ain = ain.getNext())
        {
            if (ain instanceof VarInsnNode)
            {
                Integer nv = remap.get(((VarInsnNode) ain).var);
                if (nv != null)
                    ((VarInsnNode) ain).var = nv;
            }
        }
        // redirect profiler calls to thread-safe no-ops (shared profiler stack is not thread-safe)
        for (AbstractInsnNode ain = extracted.instructions.getFirst(); ain != null; ain = ain.getNext())
        {
            if (!(ain instanceof MethodInsnNode))
                continue;
            MethodInsnNode mi = (MethodInsnNode) ain;
            if (!mi.owner.equals("net/minecraft/profiler/Profiler"))
                continue;
            boolean section = mi.name.equals("startSection") || mi.name.equals("endStartSection");
            MethodInsnNode nn = new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                    section ? "profSection" : "profEnd",
                    section ? "(Lnet/minecraft/profiler/Profiler;Ljava/lang/String;)V"
                            : "(Lnet/minecraft/profiler/Profiler;)V", false);
            extracted.instructions.set(mi, nn);
        }
        extracted.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(extracted);

        // original loop body becomes the dispatch call
        InsnList rep = new InsnList();
        rep.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rep.add(new VarInsnNode(Opcodes.ALOAD, chunkLocal));
        rep.add(new VarInsnNode(Opcodes.ILOAD, 1));
        rep.add(new VarInsnNode(Opcodes.ILOAD, 2));
        rep.add(new VarInsnNode(Opcodes.ILOAD, 3));
        rep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "callEnvTick",
                "(Lnet/minecraft/world/WorldServer;Lnet/minecraft/world/chunk/Chunk;IZZ)V", false));
        ins.insertBefore(backEdge, rep);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                ClassLoader cl = getClass().getClassLoader();
                try
                {
                    Class<?> c = Class.forName(type1.replace('/', '.'), false, cl);
                    Class<?> d = Class.forName(type2.replace('/', '.'), false, cl);
                    if (c.isAssignableFrom(d))
                        return type1;
                    if (d.isAssignableFrom(c))
                        return type2;
                    if (c.isInterface() || d.isInterface())
                        return "java/lang/Object";
                    do
                    {
                        c = c.getSuperclass();
                    }
                    while (!c.isAssignableFrom(d));
                    return c.getName().replace('.', '/');
                }
                catch (Exception e)
                {
                    return super.getCommonSuperClass(type1, type2);
                }
            }
        };
        cn.accept(cw);
        LOG.info("Patched WorldServer: env tick per-chunk dispatch + mcmt$tickEnvChunk extraction");
        return cw.toByteArray();
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
