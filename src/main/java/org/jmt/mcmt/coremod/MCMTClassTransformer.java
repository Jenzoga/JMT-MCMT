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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.Type;
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
        if (Boolean.getBoolean("mcmt.dump") && transformedName.startsWith("net.minecraft."))
            dump(transformedName, basicClass);
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
        if (transformedName.equals("net.minecraft.server.dedicated.DedicatedServer"))
        {
            try
            {
                return patchDedicatedServer(basicClass);
            }
            catch (Exception e)
            {
                LOG.error("MCMT failed to patch DedicatedServer, running without crash-report integration", e);
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
        if (transformedName.equals("net.minecraft.entity.EntityTracker"))
        {
            try
            {
                return patchEntityTracker(basicClass);
            }
            catch (Exception e)
            {
                LOG.error("MCMT failed to patch EntityTracker, running without entity parallelism safety", e);
            }
        }
        if (transformedName.equals("net.minecraftforge.common.DimensionManager"))
        {
            try
            {
                return patchDimensionManager(basicClass);
            }
            catch (Exception e)
            {
                LOG.error("MCMT failed to patch DimensionManager, running without unload quiescence", e);
            }
        }
        if (transformedName.equals("net.minecraft.world.gen.ChunkProviderServer"))
        {
            try
            {
                return patchChunkProviderServer(basicClass);
            }
            catch (Exception e)
            {
                LOG.error("MCMT failed to patch ChunkProviderServer, running without chunk-map safety", e);
            }
        }
        return basicClass;
    }

    private byte[] patchDedicatedServer(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        MethodNode m = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.desc.equals("(Lnet/minecraft/crash/CrashReport;)Lnet/minecraft/crash/CrashReport;")
                    && (mn.name.equals("addServerInfoToCrashReport") || mn.name.equals("func_71230_b")))
            {
                m = mn;
                break;
            }
        }
        if (m == null)
        {
            LOG.error("addServerInfoToCrashReport not found in DedicatedServer");
            return in;
        }
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/crash/CrashReport", "getCategory",
                "()Lnet/minecraft/crash/CrashReportCategory;", false));
        pre.add(new org.objectweb.asm.tree.LdcInsnNode("MCMT"));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "populateCrashReport",
                "()Ljava/lang/String;", false));
        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/crash/CrashReportCategory", "addCrashSection",
                "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        m.instructions.insert(pre);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        LOG.info("Patched DedicatedServer: crash-report integration");
        return cw.toByteArray();
    }

    // Dimension cycling (FML unloadWorlds during tick) must not tear a WorldServer
    // while pool threads are mid-tick: await in-flight tasks first. The method is
    // Forge-owned (unobfuscated), so the name matches in dev and prod alike.
    private byte[] patchDimensionManager(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        MethodNode m = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.name.equals("unloadWorlds")
                    && mn.desc.equals("(Ljava/util/Hashtable;)V"))
            {
                m = mn;
                break;
            }
        }
        if (m == null)
        {
            LOG.error("unloadWorlds not found in DimensionManager");
            return in;
        }
        m.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "waitInFlight",
                "()V", false));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        LOG.info("Patched DimensionManager: unload quiescence");
        return cw.toByteArray();
    }
    private byte[] patchWorldServer(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        MethodNode m = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.desc.equals("()V") && (mn.name.equals("updateBlocks") || mn.name.equals("func_147456_g")))
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

        // clone body (store..backEdge exclusive) into WorldServer.mcmt$tickEnvChunk(Chunk,IZZ)V.
        // The original body STAYS in updateBlocks so later coremods that locate
        // instructions by ordinal (e.g. RLTweaker's Chunk.onTick search) still
        // find them; the guard below routes to the clone only when parallelEnv
        // is enabled, otherwise vanilla code runs untouched.
        java.util.List<AbstractInsnNode> body = new java.util.ArrayList<>();
        for (AbstractInsnNode ain = store.getNext(); ain != backEdge; ain = ain.getNext())
            body.add(ain);

        MethodNode extracted = new MethodNode(Opcodes.ASM5, Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                "mcmt$tickEnvChunk", "(Lnet/minecraft/world/chunk/Chunk;IZZ)V", null, null);
        // canonical clone pattern: fresh LabelNodes for labels inside the body,
        // identity map for labels outside it (loop back edges), otherwise
        // clone() dereferences a null map lookup for not-yet-visited labels
        java.util.Map<LabelNode, LabelNode> labelMap = new java.util.IdentityHashMap<>();
        for (AbstractInsnNode node : body)
            if (node instanceof LabelNode)
                labelMap.put((LabelNode) node, new LabelNode());
        for (AbstractInsnNode node : body)
        {
            java.util.List<LabelNode> targets = new java.util.ArrayList<>();
            if (node instanceof JumpInsnNode)
                targets.add(((JumpInsnNode) node).label);
            else if (node instanceof LookupSwitchInsnNode)
            {
                LookupSwitchInsnNode ls = (LookupSwitchInsnNode) node;
                targets.add(ls.dflt);
                targets.addAll(ls.labels);
            }
            else if (node instanceof TableSwitchInsnNode)
            {
                TableSwitchInsnNode ts = (TableSwitchInsnNode) node;
                targets.add(ts.dflt);
                targets.addAll(ts.labels);
            }
            for (LabelNode l : targets)
                if (!labelMap.containsKey(l))
                    labelMap.put(l, l);
        }
        for (AbstractInsnNode node : body)
        {
            if (node instanceof FrameNode)
                continue; // stale locals layout; COMPUTE_FRAMES recomputes
            extracted.instructions.add(node.clone(labelMap));
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
            boolean section = mi.name.equals("startSection") || mi.name.equals("endStartSection")
                    || mi.name.equals("func_76320_a") || mi.name.equals("func_76318_c");
            MethodInsnNode nn = new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                    section ? "profSection" : "profEnd",
                    section ? "(Lnet/minecraft/profiler/Profiler;Ljava/lang/String;)V"
                            : "(Lnet/minecraft/profiler/Profiler;)V", false);
            extracted.instructions.set(mi, nn);
        }
        extracted.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(extracted);

        // guard: if (parallelEnv) { callEnvTick(...); continue; } else fall through to original body
        LabelNode originalBody = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "shouldDispatchEnv", "()Z", false));
        guard.add(new JumpInsnNode(Opcodes.IFEQ, originalBody));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, chunkLocal));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 1));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 2));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 3));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "callEnvTick",
                "(Lnet/minecraft/world/WorldServer;Lnet/minecraft/world/chunk/Chunk;IZZ)V", false));
        guard.add(new JumpInsnNode(Opcodes.GOTO, ((JumpInsnNode) backEdge).label));
        guard.add(originalBody);
        ins.insertBefore(store.getNext(), guard);

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
        // Pool-thread entity/TE ticks can schedule block updates while this
        // world's tick drains the scheduled-tick lists; serialize both sides
        // on the TreeSet monitor so the size invariant holds. Every TreeSet
        // acquisition first takes loadedEntityList: pool-thread tickUpdates
        // reaches spawnEntity/removeEntity via BlockFalling.checkFallable, so
        // tree->entity ordering would invert against updateEntities' entity->tree
        // iteration and deadlock (found by DevChurn: Server thread holds loaded
        // waiting tree, pool thread holds tree waiting loaded).
        String[] entityField2 = { "loadedEntityList", "field_72996_f" };
        String[] tickListField = { "pendingTickListEntriesTreeSet", "field_73065_O" };
        String[] chunkMapField = { "loadedChunks", "field_73244_f" };
        synchronizeOnFields(cn, new String[] { "scheduleBlockUpdate", "func_180497_b" },
                "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/WorldServer" } },
                new String[][] { chunkMapField, entityField2, tickListField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/TreeSet;" },
                "loadedEntityList+pendingTickListEntriesTreeSet");
        synchronizeOnFields(cn, new String[] { "updateBlockTick", "func_175654_a" },
                "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;II)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/WorldServer" } },
                new String[][] { chunkMapField, entityField2, tickListField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/TreeSet;" },
                "loadedEntityList+pendingTickListEntriesTreeSet");
        synchronizeOnFields(cn, new String[] { "isBlockTickPending", "func_175691_a" },
                "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/WorldServer" } },
                new String[][] { chunkMapField, entityField2, tickListField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/TreeSet;" },
                "loadedEntityList+pendingTickListEntriesTreeSet");
        synchronizeOnFields(cn, new String[] { "isUpdateScheduled", "func_184145_b" },
                "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/WorldServer" } },
                new String[][] { chunkMapField, entityField2, tickListField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/TreeSet;" },
                "loadedEntityList+pendingTickListEntriesTreeSet");
        synchronizeOnFields(cn, new String[] { "tickUpdates", "func_72955_a" }, "(Z)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/WorldServer" } },
                new String[][] { chunkMapField, entityField2, tickListField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/TreeSet;" },
                "loadedEntityList+pendingTickListEntriesTreeSet");
        synchronizeOnFields(cn, new String[] { "getPendingBlockUpdates", "func_175712_a" },
                "(Lnet/minecraft/world/gen/structure/StructureBoundingBox;Z)Ljava/util/List;",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/WorldServer" } },
                new String[][] { chunkMapField, entityField2, tickListField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/TreeSet;" },
                "loadedEntityList+pendingTickListEntriesTreeSet");

        // WorldServer overrides World's entity-list entry points, so the World
        // wrappers alone do not cover virtual dispatch on a WorldServer `this`.
        // Wrapping updateEntities itself also keeps foamfix's WorldServerRemovalPatch
        // from injecting its iterator into the method (its SIPUSH 300/IFNULL
        // injection anchor is absent from the wrapper body) - that injected
        // iterator was the CME crash site in the 219-mod pack.
        String[] entityField = { "loadedEntityList", "field_72996_f" };
        String[] unloadedField = { "unloadedEntityList", "field_72997_g" };
        String[] chunkMap = { "loadedChunks", "field_73244_f" };
        // updateEntities iterates BOTH entity lists (vanilla removal block and
        // foamfix's hoisted removeUnloadedEntities), while pool threads add to
        // unloadedEntityList via Chunk.setChunkDataFromThis -> World.unloadEntities
        // during chunk loading in world.tick; lock both monitors in order.
        // loadedChunks FIRST: World.updateEntities calls getChunk (provider map)
        // after taking the entity lists, while pool-thread ChunkProviderServer.tick
        // mutates the map and reaches unloadEntities from it — entity->chunks would
        // invert against chunks->entity and deadlock (DevChurn stack: Server thread
        // holds entity lists waiting loadedChunks, pool thread holds loadedChunks
        // waiting entity lists via Chunk.onUnload -> unloadEntities).
        synchronizeOnFields(cn, new String[] { "updateEntities", "func_72939_s" }, "()V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer",
                        "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/World" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/World" } },
                new String[][] { { "loadedChunks", "field_73244_f" }, entityField, unloadedField,
                        { "weatherEffects", "field_73007_j" },
                        { "tickableTileEntities", "field_175730_i" } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/List;", "Ljava/util/List;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList+unloadedEntityList+weatherEffects+tickableTileEntities");
        // loadedChunks is the outermost lock everywhere: World.getChunk ->
        // provideChunk mutates the provider map from inside entity-lock holders,
        // and pool-thread provider-map holders reach entity lists via
        // Chunk.onUnload/setChunkDataFromThis. Every entity wrapper takes it
        // first or the hierarchy inverts (DevChurn deadlock sweep).
        synchronizeOnFields(cn, new String[] { "addWeatherEffect", "func_72942_c" },
                "(Lnet/minecraft/entity/Entity;)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMap, { "weatherEffects", "field_73007_j" } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+weatherEffects");
        synchronizeOnFields(cn, new String[] { "spawnEntity", "func_72838_d" },
                "(Lnet/minecraft/entity/Entity;)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMap, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        synchronizeOnFields(cn, new String[] { "onEntityRemoved", "func_72847_b" },
                "(Lnet/minecraft/entity/Entity;)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMap, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        // WorldServer overrides updateEntityWithOptionalForce (spawn gating +
        // super() into the World wrapper); without this override wrapper virtual
        // dispatch from pool-thread setPosition bypasses the World wrapper.
        synchronizeOnFields(cn, new String[] { "updateEntityWithOptionalForce", "func_72866_a" },
                "(Lnet/minecraft/entity/Entity;Z)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMap, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        synchronizeOnFields(cn, new String[] { "onEntityAdded", "func_72923_a" },
                "(Lnet/minecraft/entity/Entity;)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMap, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        // WorldServer does not override removeEntity: the World wrapper covers it,
        // and EntityTracker.track/untrack wrappers already serialize the entries Set.

        cn.accept(cw);
        LOG.info("Patched WorldServer: env tick per-chunk dispatch + mcmt$tickEnvChunk extraction + scheduled-tick sync");
        byte[] out = cw.toByteArray();
        if (Boolean.getBoolean("mcmt.dump"))
            dump("net.minecraft.world.WorldServer", out);
        return out;
    }

    private void dump(String name, byte[] bytes)
    {
        try
        {
            java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "mcmt-dump");
            dir.mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(
                    new java.io.File(dir, name.replace('.', '_') + ".class"));
            fos.write(bytes);
            fos.close();
        }
        catch (Exception ignored)
        {
        }
    }

    private byte[] patchEntityTracker(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        // entries is a private Set<EntityTrackerEntry>; private fields are not
        // mapped, so resolve by descriptor instead of name (dev MCP vs prod notch)
        String entriesName = null;
        for (FieldNode fn : cn.fields)
        {
            if (fn.desc.equals("Ljava/util/Set;"))
            {
                entriesName = fn.name;
                break;
            }
        }
        if (entriesName == null)
        {
            LOG.error("EntityTracker.entries Set field not found");
            return in;
        }
        // EntityTracker.tick iterates entries on the main thread while pool-thread
        // entity add/remove reaches track/untrack via WorldServer.onEntity* (CME)
        // loadedChunks first: EntityTrackerEntry.updatePlayerChunk reaches
        // getChunk->provideChunk while this holds entries; provider-map holders
        // must never wait on entries behind entity lists they already passed.
        synchronizeOnFields(cn, new String[] { "tick", "func_72788_a" }, "()V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer",
                        "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/entity/EntityTracker" } },
                new String[][] { { "loadedChunks", "field_73244_f" }, { entriesName } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/Set;" },
                "loadedChunks+trackerEntries");
        synchronizeOnField(cn, new String[] { "track", "func_72786_a" }, "(Lnet/minecraft/entity/Entity;)V",
                "net/minecraft/entity/EntityTracker", new String[] { entriesName }, "Ljava/util/Set;",
                "trackerEntries");
        synchronizeOnField(cn, new String[] { "track", "func_72791_a" }, "(Lnet/minecraft/entity/Entity;II)V",
                "net/minecraft/entity/EntityTracker", new String[] { entriesName }, "Ljava/util/Set;",
                "trackerEntries");
        synchronizeOnField(cn, new String[] { "untrack", "func_72790_b" }, "(Lnet/minecraft/entity/Entity;)V",
                "net/minecraft/entity/EntityTracker", new String[] { entriesName }, "Ljava/util/Set;",
                "trackerEntries");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                try
                {
                    return super.getCommonSuperClass(type1, type2);
                }
                catch (Exception e)
                {
                    return "java/lang/Object";
                }
            }
        };
        cn.accept(cw);
        LOG.info("Patched EntityTracker: trackerEntries synchronization");
        return cw.toByteArray();
    }

    // loadedChunks is a plain Long2ObjectOpenHashMap iterated by
    // ChunkProviderServer.tick on every pool-thread world tick while pool-thread
    // chunk loads (entity-tick side effects) and harness-style queueUnload mutate
    // it -> CME/AIOOBE crashes (found by DevChurn). Serialize map mutations and
    // the tick's iteration on the map's own monitor. Lock hierarchy: loadedChunks
    // sits above entity lists (chunk loads reach unloadEntities), so nothing may
    // take loadedChunks while holding entity locks — updateEntities/getPersistentChunks
    // do not, verified against bytecode.
    private byte[] patchChunkProviderServer(byte[] in)
    {
        ClassNode cn = new ClassNode();
        new ClassReader(in).accept(cn, 0);
        // provideChunk is NOT wrapped: it runs full chunk GENERATION (modded
        // worldgen, e.g. BetterCaves parallelStream) inside any monitor we take,
        // and generator threads re-enter provideChunk for neighbor chunks ->
        // nested parallelStream workers block on the held monitor and deadlock
        // (prodtest hang jstack 2026-10-12). Map safety is covered by the
        // tick/saveChunks/loadChunk/queueUnload wrappers; unlocked generation
        // reads are benign (computeIfAbsent semantics).
        synchronizeOnField(cn, new String[] { "loadChunk", "func_186028_c" },
                "(II)Lnet/minecraft/world/chunk/Chunk;", "net/minecraft/world/gen/ChunkProviderServer",
                new String[] { "loadedChunks", "field_73244_f" },
                "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "loadedChunks");
        synchronizeOnField(cn, new String[] { "queueUnload", "func_189549_a" },
                "(Lnet/minecraft/world/chunk/Chunk;)V", "net/minecraft/world/gen/ChunkProviderServer",
                new String[] { "loadedChunks", "field_73244_f" },
                "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "loadedChunks");
        synchronizeOnField(cn, new String[] { "tick", "func_73156_b" }, "()Z",
                "net/minecraft/world/gen/ChunkProviderServer",
                new String[] { "loadedChunks", "field_73244_f" },
                "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "loadedChunks");
        synchronizeOnField(cn, new String[] { "saveChunks", "func_186027_a" }, "(Z)Z",
                "net/minecraft/world/gen/ChunkProviderServer",
                new String[] { "loadedChunks", "field_73244_f" },
                "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "loadedChunks");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
        {
            @Override
            protected String getCommonSuperClass(String type1, String type2)
            {
                try
                {
                    return super.getCommonSuperClass(type1, type2);
                }
                catch (Exception e)
                {
                    return "java/lang/Object";
                }
            }
        };
        cn.accept(cw);
        byte[] out = cw.toByteArray();
        if (Boolean.getBoolean("mcmt.dump"))
            dump("net.minecraft.world.gen.ChunkProviderServer", out);
        LOG.info("Patched ChunkProviderServer: loadedChunks synchronization");
        return out;
    }

    private void synchronizeOnField(ClassNode cn, String[] names, String desc, String fieldOwner,
            String[] fieldNames, String fieldType, String logName)
    {
        synchronizeOnFields(cn, names, desc, new String[][] { { fieldOwner } },
                new String[][] { fieldNames }, new String[] { fieldType }, logName);
    }

    // fieldOwners[i][j]: declaring owner of candidate name fieldNamesList[i][j].
    // fieldOwners[i].length must equal fieldNamesList[i].length.
    private void synchronizeOnFields(ClassNode cn, String[] names, String desc, String[][] fieldOwners,
            String[][] fieldNamesList, String[] fieldTypes, String logName)
    {
        MethodNode m = null;
        for (MethodNode mn : cn.methods)
        {
            if (!mn.desc.equals(desc))
                continue;
            for (String n : names)
            {
                if (mn.name.equals(n))
                {
                    m = mn;
                    break;
                }
            }
            if (m != null)
                break;
        }
        if (m == null)
        {
            LOG.error("sync target not found: " + names[0]);
            return;
        }
        String[] resolved = new String[fieldNamesList.length];
        String[] owners = new String[fieldNamesList.length];
        for (int i = 0; i < fieldNamesList.length; i++)
        {
            String[] fieldNames = fieldNamesList[i];
            String[] fieldOwner = fieldOwners[i];
            String fieldName = null;
            String fieldOwn = null;
            // 1) declared in this class or any superclass: exact name + descriptor
            //    match, walking the real hierarchy so inherited/private fields get
            //    the declaring owner (asserted call-site owners are advisory only)
            boolean declaredWrongType = false;
            for (FieldNode fn : cn.fields)
            {
                for (int j = 0; j < fieldNames.length; j++)
                {
                    if (fn.name.equals(fieldNames[j]))
                    {
                        if (!fn.desc.equals(fieldTypes[i]))
                        {
                            declaredWrongType = true;
                            break;
                        }
                        fieldName = fn.name;
                        fieldOwn = cn.name;
                        break;
                    }
                }
                if (fieldName != null || declaredWrongType)
                    break;
            }
            if (fieldName == null && !declaredWrongType)
            {
                // private/unmapped field (e.g. EntityTracker.entries): candidates are
                // not SRG/MCP names, resolve by unique descriptor match here
                for (FieldNode fn : cn.fields)
                    if (fn.desc.equals(fieldTypes[i]))
                    {
                        if (fieldName != null)
                        {
                            fieldName = null; // ambiguous: two same-typed fields
                            break;
                        }
                        fieldName = fn.name;
                        fieldOwn = cn.name;
                    }
            }
            if (fieldName == null)
            {
                // not declared here: inherited (or foreign). Pick the candidate
                // matching this class's naming mode, then ask the runtime class
                // hierarchy which class actually declares it.
                boolean srg = false;
                for (MethodNode mn : cn.methods)
                {
                    if (mn.name.startsWith("func_"))
                    {
                        srg = true;
                        break;
                    }
                }
                int matchIdx = -1;
                for (int j = 0; j < fieldNames.length; j++)
                    if (fieldNames[j].startsWith("field_") == srg)
                    {
                        fieldName = fieldNames[j];
                        matchIdx = j;
                        break;
                    }
                if (fieldName != null)
                {
                    // reflection cannot see real field names in dev (the MC jar
                    // on the dev classpath is still obfuscated; deobf happens at
                    // class-load time), so trust the asserted owner: wrappers on
                    // subclasses of the declaring class emit a correct GETFIELD,
                    // and cross-hierarchy loadedChunks goes through emitMonitor's
                    // accessor regardless of the asserted owner.
                    fieldOwn = fieldOwner[Math.max(0, Math.min(matchIdx, fieldOwner.length - 1))];
                }
            }
            if (fieldName == null)
            {
                LOG.error(logName + " field not found in " + cn.name);
                return;
            }
            resolved[i] = fieldName;
            owners[i] = fieldOwn;
        }

        // Rename the original and emit a wrapper that runs it inside
        // synchronized(f1), synchronized(f2), ... in order. The wrapper uses
        // no new locals so the frame layout of the exception handler stays
        // trivially correct under COMPUTE_FRAMES.
        String wrapperName = m.name;
        String origName = m.name + "$mcmt$safe";
        m.name = origName;

        MethodNode w = new MethodNode(Opcodes.ASM5, m.access, wrapperName, desc, null, null);
        InsnList b = w.instructions;
        LabelNode start = new LabelNode();
        LabelNode handler = new LabelNode();
        b.add(start);
        for (int i = 0; i < resolved.length; i++)
            emitMonitor(b, owners[i], resolved[i], fieldTypes[i], true);
        b.add(new VarInsnNode(Opcodes.ALOAD, 0));
        b.add(new LdcInsnNode(wrapperName + " enter"));
        b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "debugTickSize",
                "(Ljava/lang/Object;Ljava/lang/String;)V", false));
        b.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int v = 0;
        for (Type t : Type.getArgumentTypes(desc))
        {
            v++;
            b.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), v));
        }
        // INVOKESPECIAL: bind the renamed original exactly to this class.
        // INVOKEVIRTUAL would let a subclass's renamed original (reached via
        // a super() call chain) dispatch back into this wrapper forever.
        b.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cn.name, origName, desc, false));
        b.add(new VarInsnNode(Opcodes.ALOAD, 0));
        b.add(new LdcInsnNode(wrapperName + " exit"));
        b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "debugTickSize",
                "(Ljava/lang/Object;Ljava/lang/String;)V", false));
        for (int i = resolved.length - 1; i >= 0; i--)
            emitMonitor(b, owners[i], resolved[i], fieldTypes[i], false);
        b.add(new InsnNode(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN)));
        b.add(handler);
        for (int i = resolved.length - 1; i >= 0; i--)
            emitMonitor(b, owners[i], resolved[i], fieldTypes[i], false);
        b.add(new InsnNode(Opcodes.ATHROW));
        w.tryCatchBlocks.add(new TryCatchBlockNode(start, handler, handler, null));
        cn.methods.add(w);
        LOG.info("Synchronized " + w.name + " on " + logName);
    }

    // Emit the monitor acquisition for one wrapper lock. loadedChunks lives on
    // ChunkProviderServer, unreachable by GETFIELD from World/WorldServer/
    // EntityTracker wrappers, so it routes through the hook accessor instead.
    private void emitMonitor(InsnList b, String owner, String name, String desc, boolean enter)
    {
        if ("loadedChunks".equals(name) || "field_73244_f".equals(name))
        {
            b.add(new VarInsnNode(Opcodes.ALOAD, 0));
            b.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "getLoadedChunks",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        }
        else
        {
            b.add(new VarInsnNode(Opcodes.ALOAD, 0));
            b.add(new FieldInsnNode(Opcodes.GETFIELD, owner, name, desc));
        }
        b.add(new InsnNode(enter ? Opcodes.MONITORENTER : Opcodes.MONITOREXIT));
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
                        && (mi.name.equals("update") || mi.name.equals("func_73660_a"))
                        && mi.desc.equals("()V"))
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

        // entity list mutation sites: pool-thread spawn/remove must not race
        // main-thread iteration of loadedEntityList (foamfix CME crash)
        // Same hierarchy as WorldServer: loadedChunks first (World.getChunk ->
        // provideChunk is reachable from inside every one of these while holding
        // the entity list lock). Wrappers are reentrant-safe when WorldServer's
        // wrapper already holds loadedChunks higher in the stack.
        String[] entityField = { "loadedEntityList", "field_72996_f" };
        String[] chunkMapW = { "loadedChunks", "field_73244_f" };
        synchronizeOnFields(cn, new String[] { "spawnEntity", "func_72838_d" },
                "(Lnet/minecraft/entity/Entity;)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMapW, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        // removeEntity is not overridden by WorldServer: the World wrapper already
        // covers it (INVOKESPECIAL delegates to the World original), so no wrapper here.
        // countEntities iterates loadedEntityList (WorldEntitySpawner calls it on
        // pool threads via WorldServer.tick) — read-side iteration of the same
        // list concurrent spawn/remove mutates needs the monitor too.
        synchronizeOnFields(cn, new String[] { "countEntities", "func_72907_a" },
                "(Ljava/lang/Class;)I",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMapW, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        // updateEntityWithOptionalForce mutates per-chunk entity lists (Chunk.
        // addEntity/removeEntityAtIndex when an entity crosses chunk borders) and
        // reads ForgeChunkManager.forcedChunks, while AABB queries iterate those
        // lists — pool-thread Entity.setPosition hits it inside the entity tick
        // (DevChurn CME at Chunk.getEntitiesOfTypeWithinAABB). loadedChunks first
        // covers the forcedChunks read; tickable last matches updateEntities.
        synchronizeOnFields(cn, new String[] { "updateEntityWithOptionalForce", "func_72866_a" },
                "(Lnet/minecraft/entity/Entity;Z)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" }, { "net/minecraft/world/World" } },
                new String[][] { chunkMapW, entityField,
                        { "tickableTileEntities", "field_175730_i" } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;",
                        "Ljava/util/List;" },
                "loadedChunks+loadedEntityList+tickableTileEntities");
        // TE list mutations: pool-thread ITickable.update side effects reach
        // addTileEntity/removeTileEntity (lazy TE add via getTileEntity, block
        // changes) while updateEntities iterates tickableTileEntities on the
        // server thread -> CME class. Both methods call getChunk internally, so
        // loadedChunks goes first (same order as updateEntities); no entity
        // locks inside their bodies, so tickable-at-the-end cannot invert.
        synchronizeOnFields(cn, new String[] { "addTileEntity", "func_175700_a" },
                "(Lnet/minecraft/tileentity/TileEntity;)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { { "loadedChunks", "field_73244_f" },
                        { "tickableTileEntities", "field_175730_i" } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+tickableTileEntities");
        synchronizeOnFields(cn, new String[] { "removeTileEntity", "func_175713_t" },
                "(Lnet/minecraft/util/math/BlockPos;)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { { "loadedChunks", "field_73244_f" },
                        { "tickableTileEntities", "field_175730_i" } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+tickableTileEntities");
        synchronizeOnFields(cn, new String[] { "onEntityRemoved", "func_72847_b" },
                "(Lnet/minecraft/entity/Entity;)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMapW, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        synchronizeOnFields(cn, new String[] { "removeEntityDangerously", "func_72973_f" },
                "(Lnet/minecraft/entity/Entity;)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMapW, entityField },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+loadedEntityList");
        synchronizeOnFields(cn, new String[] { "unloadEntities", "func_175681_c" },
                "(Ljava/util/Collection;)V",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMapW, { "unloadedEntityList", "field_72997_g" } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+unloadedEntityList");
        // weatherEffects is iterated in updateEntities and added to by lightning
        // /weather entities spawned from entity ticks
        synchronizeOnFields(cn, new String[] { "addWeatherEffect", "func_72942_c" },
                "(Lnet/minecraft/entity/Entity;)Z",
                new String[][] { { "net/minecraft/world/gen/ChunkProviderServer", "net/minecraft/world/gen/ChunkProviderServer" },
                        { "net/minecraft/world/World" } },
                new String[][] { chunkMapW, { "weatherEffects", "field_73007_j" } },
                new String[] { "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;", "Ljava/util/List;" },
                "loadedChunks+weatherEffects");

        // COMPUTE_FRAMES: the loadedEntityList sync wrappers add try/catch
        // handler entries that need stackmap frames (COMPUTE_MAXS leaves them
        // missing -> VerifyError "Expecting a stackmap frame at branch target")
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        byte[] out = cw.toByteArray();
        if (Boolean.getBoolean("mcmt.dump"))
            dump("net.minecraft.world.World", out);
        return out;
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
                    && (mi.name.equals("tick") || mi.name.equals("func_72835_b"))
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

        // Dev-only churn harness entry: run() is un-mapped in both dev and prod,
        // boot() no-ops unless -Dmcmt.devchurn=true.
        MethodNode run = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.desc.equals("()V") && mn.name.equals("run"))
            {
                run = mn;
                break;
            }
        }
        if (run != null)
        {
            InsnList boot = new InsnList();
            boot.add(new VarInsnNode(Opcodes.ALOAD, 0));
            boot.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/jmt/mcmt/coremod/DevChurn", "boot",
                    "(Lnet/minecraft/server/MinecraftServer;)V", false));
            run.instructions.insert(boot);
            LOG.info("Patched MinecraftServer: churn harness entry");
        }

        // Post-world-tick mod handlers (BetterSurvival/switchbow iterate
        // loadedEntityList unlocked) run while pool tasks are still in flight;
        // wrap each FMLCommonHandler.onPostWorldTick call site with the entity
        // monitor pair so mod iteration serializes against wrapper mutations.
        int q2 = 0;
        for (AbstractInsnNode ain = m.instructions.getFirst(); ain != null; )
        {
            AbstractInsnNode next = ain.getNext();
            if (ain instanceof MethodInsnNode)
            {
                MethodInsnNode mi = (MethodInsnNode) ain;
                if (mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && mi.owner.equals("net/minecraftforge/fml/common/FMLCommonHandler")
                        && (mi.name.equals("onPostWorldTick") || mi.name.equals("func_71385_c"))
                        && mi.desc.equals("(Lnet/minecraft/world/World;)V"))
                {
                    // updateTimeLightAndEntities keeps the current WorldServer in
                    // local slot 6 (astore 6 after DimensionManager.getWorld);
                    // enter loadedChunks then loadedEntityList, matching the
                    // wrapper hierarchy; a mod-handler exception tears the
                    // server down anyway (vanilla crash path), so no finally
                    InsnList wIn = new InsnList();
                    wIn.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    wIn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "getLoadedChunks",
                            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    wIn.add(new InsnNode(Opcodes.MONITORENTER));
                    wIn.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    wIn.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "getLoadedEntityList",
                            "(Lnet/minecraft/world/World;)Ljava/lang/Object;", false));
                    wIn.add(new InsnNode(Opcodes.MONITORENTER));
                    m.instructions.insertBefore(mi, wIn);
                    InsnList wOut = new InsnList();
                    wOut.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    wOut.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "getLoadedEntityList",
                            "(Lnet/minecraft/world/World;)Ljava/lang/Object;", false));
                    wOut.add(new InsnNode(Opcodes.MONITOREXIT));
                    wOut.add(new VarInsnNode(Opcodes.ALOAD, 6));
                    wOut.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "getLoadedChunks",
                            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
                    wOut.add(new InsnNode(Opcodes.MONITOREXIT));
                    m.instructions.insert(mi, wOut);
                    q2++;
                }
            }
            ain = next;
        }
        if (q2 == 0)
            LOG.error("onPostWorldTick call site not found in updateTimeLightAndEntities");
        else
            LOG.info("Patched MinecraftServer: post-tick entity monitor x" + q2);

        // Shutdown quiescence: await in-flight pool tick tasks before the server
        // tears worlds down (unloadWorlds races loadedChunkMap otherwise).
        MethodNode stop = null;
        for (MethodNode mn : cn.methods)
        {
            if (mn.desc.equals("()V")
                    && (mn.name.equals("stopServer") || mn.name.equals("func_71260_j")))
            {
                stop = mn;
                break;
            }
        }
        if (stop != null)
        {
            InsnList q = new InsnList();
            q.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "quiesce", "()V", false));
            stop.instructions.insert(q);
            LOG.info("Patched MinecraftServer: shutdown quiescence");
        }
        else
            LOG.error("stopServer not found in MinecraftServer, shutdown quiescence missing");
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
