import com.nothome.delta.GDiffPatcher;
import com.nothome.delta.GDiffWriter;
import lzma.streams.LzmaOutputStream;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Synthesizes devbinpatches.pack.lzma (binpatch/merged/*) for ForgeGradle 2.3
 * dev environments when the official userdev artifact is unavailable.
 *
 * Inputs: client jar, server(-pure) jar, unpacked binpatch jar (containing
 * binpatch/{client,server}/*.binpatch entries), the unpatched merged jar.
 * Output: LZMA-compressed pack200 jar of merged binpatches usable by
 * TaskApplyBinPatches.
 */
public class GenMergedPatches
{
    static final String SIDE_ONLY = "Lnet/minecraftforge/fml/relauncher/SideOnly;";
    static final String SIDE = "Lnet/minecraftforge/fml/relauncher/Side;";

    static class PatchInfo
    {
        byte[] patch;
        boolean exists;
        String target;
    }

    static Map<String, String> mdMap = new HashMap<String, String>();
    static Map<String, String> mcpToNotch = new HashMap<String, String>();

    public static void main(String[] args) throws Exception
    {
        File clientJar = new File(args[0]);
        File serverJar = new File(args[1]);
        File mergedJar = new File(args[2]);
        File unpackedPatchJar = new File(args[3]);
        File outLzma = new File(args[4]);
        if (args.length > 5)
            loadMappings(new File(args[5]));

        Map<String, PatchInfo> cPatches = loadPatches(unpackedPatchJar, "client");
        Map<String, PatchInfo> sPatches = loadPatches(unpackedPatchJar, "server");
        System.out.println("loaded patches client=" + cPatches.size() + " server=" + sPatches.size());

        Map<String, byte[]> pc = applyAll(clientJar, cPatches);
        Map<String, byte[]> ps = applyAll(serverJar, sPatches);
        System.out.println("patched client=" + pc.size() + " server=" + ps.size());

        // merge patched sides the same way MergeJars merges unpatched ones
        Map<String, byte[]> pm = new LinkedHashMap<String, byte[]>();
        for (Map.Entry<String, byte[]> e : pc.entrySet())
        {
            byte[] s = ps.get(e.getKey());
            if (s == null)
                pm.put(e.getKey(), addClassSideAnn(e.getValue(), true));
            else
                pm.put(e.getKey(), processClass(e.getValue(), s));
        }
        for (Map.Entry<String, byte[]> e : ps.entrySet())
        {
            if (!pc.containsKey(e.getKey()))
                pm.put(e.getKey(), addClassSideAnn(e.getValue(), false));
        }
        System.out.println("merged patched classes=" + pm.size());

        // exists=false server patches create classes from nothing. Where such a
        // class already exists in merged (client-only inner class, annotated
        // @SideOnly(CLIENT) by MergeJars), standard dev replaces it with the
        // unannotated created version so SideTransformer accepts it on SERVER.
        // Emit a merged patch for exactly that replacement; TaskApplyBinPatches
        // would otherwise keep the annotated entry and crash on load.
        ZipFile mz = new ZipFile(mergedJar);
        GDiffPatcher gp = new GDiffPatcher();
        int created = 0;
        for (Map.Entry<String, PatchInfo> e : sPatches.entrySet())
        {
            PatchInfo pi = e.getValue();
            if (pi.exists)
                continue;
            String key = e.getKey();
            if (!pm.containsKey(key) && mz.getEntry(key) == null)
            {
                String n2 = mcpToNotch.get(pi.target);
                if (n2 != null && mz.getEntry(n2 + ".class") != null)
                    key = n2 + ".class";
            }
            if (!pm.containsKey(key) && mz.getEntry(key) == null)
                continue; // not in merged; flows through classes.jar instead
            byte[] cls = gp.patch(new byte[0], pi.patch);
            cls = renameInterfaceMethods(cls);
            pm.put(key, cls);
            created++;
        }
        mz.close();
        System.out.println("exists=false overrides=" + created);

        // diff unpatched merged vs patched merged
        int changed = 0, total = 0;
        File rawJar = File.createTempFile("mergedpatches", ".jar");
        try
        {
            ZipOutputStream jar = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(rawJar)));
            ZipFile merged = new ZipFile(mergedJar);
            Enumeration<? extends ZipEntry> en = merged.entries();
            while (en.hasMoreElements())
            {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.endsWith(".class") || name.contains("META-INF"))
                    continue;
                total++;
                byte[] orig = readAll(merged.getInputStream(e));
                byte[] mod = pm.get(name);
                if (mod == null || Arrays.equals(orig, mod))
                    continue;
                changed++;
                String cls = name.substring(0, name.length() - 6); // strip .class
                String dotted = cls.replace('/', '.');
                ByteArrayOutputStream pb = new ByteArrayOutputStream();
                ByteArrayOutputStream diff = new ByteArrayOutputStream();
                makeDiff(orig, mod, diff);
                byte[] diffBytes = diff.toByteArray();
                DataOutputStream d = new DataOutputStream(pb);
                d.writeUTF(dotted);
                d.writeUTF(dotted);
                d.writeUTF(dotted);
                d.writeBoolean(true);
                d.writeInt(adler(orig));
                d.writeInt(diffBytes.length);
                d.write(diffBytes);
                d.flush();
                JarEntry je = new JarEntry("binpatch/merged/" + cls + ".binpatch");
                jar.putNextEntry(je);
                jar.write(pb.toByteArray());
                jar.closeEntry();
            }
            merged.close();
            jar.close();
            System.out.println("classes=" + total + " changed=" + changed);
            if (changed == 0)
                throw new RuntimeException("no changed classes - something is wrong");

            // pack200 then lzma
            File packed = File.createTempFile("mergedpatches", ".pack");
            try (FileOutputStream fos = new FileOutputStream(packed))
            {
                try (JarInputStream jis = new JarInputStream(new FileInputStream(rawJar)))
                {
                    Pack200.Packer p = Pack200.newPacker();
                    p.properties().put(Pack200.Packer.EFFORT, "1");
                    p.pack(jis, fos);
                }
            }
            long srcLen = packed.length();
            try (OutputStream raw = new BufferedOutputStream(new FileOutputStream(outLzma)))
            {
                lzma.sdk.lzma.Encoder enc = new lzma.sdk.lzma.Encoder();
                enc.setDictionarySize(1 << 23);
                enc.setNumFastBytes(64);
                enc.setMatchFinder(lzma.sdk.lzma.Encoder.EMatchFinderTypeBT4);
                enc.setLcLpPb(3, 0, 2);
                enc.setEndMarkerMode(false);
                ByteArrayOutputStream props = new ByteArrayOutputStream();
                enc.writeCoderProperties(props);
                raw.write(props.toByteArray());
                for (int i = 0; i < 8; i++)
                    raw.write((byte) (srcLen >>> (8 * i)));
                FileInputStream in = new FileInputStream(packed);
                enc.code(in, raw, -1, -1, null);
                in.close();
            }
            packed.delete();
        }
        finally
        {
            rawJar.delete();
        }
        System.out.println("wrote " + outLzma);
    }

    static int adler(byte[] b)
    {
        Adler32 a = new Adler32();
        a.update(b);
        return (int) a.getValue();
    }

    static Map<String, PatchInfo> loadPatches(File jarFile, String side) throws IOException
    {
        Map<String, PatchInfo> map = new HashMap<String, PatchInfo>();
        ZipFile z = new ZipFile(jarFile);
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements())
        {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (!name.startsWith("binpatch/" + side + "/") || !name.endsWith(".binpatch"))
                continue;
            byte[] all = readAll(z.getInputStream(e));
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(all));
            String tgt = d.readUTF();
            String src = d.readUTF();
            d.readUTF();
            boolean exists = d.readBoolean();
            if (exists)
                d.readInt();
            int len = d.readInt();
            byte[] patch = new byte[len];
            d.readFully(patch);
            PatchInfo pi = new PatchInfo();
            pi.patch = patch;
            pi.exists = exists;
            pi.target = tgt.replace('.', '/');
            String cls = src.replace('.', '/') + ".class";
            map.put(cls, pi);
        }
        z.close();
        return map;
    }

    static Map<String, byte[]> applyAll(File jarFile, Map<String, PatchInfo> patches) throws IOException
    {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        GDiffPatcher g = new GDiffPatcher();
        ZipFile z = new ZipFile(jarFile);
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements())
        {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (!name.endsWith(".class") || name.contains("META-INF"))
                continue;
            byte[] data = readAll(z.getInputStream(e));
            PatchInfo p = patches.get(name);
            if (p != null && p.exists)
                data = g.patch(data, p.patch);
            out.put(name, data);
        }
        z.close();
        return out;
    }

    static void loadMappings(File notchMcpSrg) throws IOException
    {
        BufferedReader br = new BufferedReader(new FileReader(notchMcpSrg));
        String line;
        while ((line = br.readLine()) != null)
        {
            String[] p = line.trim().split("\\s+");
            if (line.startsWith("MD:") && p.length == 5)
            {
                String[] src = p[1].split("/");
                String[] dst = p[3].split("/");
                mdMap.put(src[0] + "." + src[1] + " " + p[2], dst[dst.length - 1]);
            }
            else if (line.startsWith("CL:") && p.length == 3)
            {
                mcpToNotch.put(p[2], p[1]);
            }
        }
        br.close();
        System.out.println("mappings methods=" + mdMap.size() + " classes=" + mcpToNotch.size());
    }

    static byte[] renameInterfaceMethods(byte[] cls)
    {
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(cls);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        boolean changed = false;
        for (MethodNode mn : cn.methods)
        {
            String found = null;
            for (String iface : cn.interfaces)
            {
                found = mdMap.get(iface + "." + mn.name + " " + mn.desc);
                if (found != null)
                    break;
            }
            if (found == null)
                found = mdMap.get(cn.superName + "." + mn.name + " " + mn.desc);
            if (found != null && !found.equals(mn.name))
            {
                mn.name = found;
                changed = true;
            }
        }
        if (!changed)
            return cls;
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static byte[] addClassSideAnn(byte[] data, boolean clientOnly)
    {
        ClassNode cn = node(data);
        if (cn.visibleAnnotations == null)
            cn.visibleAnnotations = new ArrayList<AnnotationNode>();
        cn.visibleAnnotations.add(sideAnn(clientOnly));
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(w);
        return w.toByteArray();
    }

    static AnnotationNode sideAnn(boolean clientOnly)
    {
        AnnotationNode ann = new AnnotationNode(SIDE_ONLY);
        ann.values = new ArrayList<Object>();
        ann.values.add("value");
        ann.values.add(new String[] { SIDE, clientOnly ? "CLIENT" : "SERVER" });
        return ann;
    }

    static ClassNode node(byte[] data)
    {
        ClassReader r = new ClassReader(data);
        ClassNode cn = new ClassNode();
        r.accept(cn, 0);
        return cn;
    }

    // ---- MergeJars.processClass logic (copied from ForgeGradle 2.3) ----

    static byte[] processClass(byte[] cIn, byte[] sIn)
    {
        ClassNode c = node(cIn);
        ClassNode s = node(sIn);
        processFields(c, s);
        processMethods(c, s);
        processInners(c, s);
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        c.accept(w);
        return w.toByteArray();
    }

    static AnnotationNode memberSideAnn(ClassNode owner, AnnotationNode existing, boolean clientOnly)
    {
        return sideAnn(clientOnly);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void processFields(ClassNode cClass, ClassNode sClass)
    {
        List<FieldNode> cFields = cClass.fields;
        List<FieldNode> sFields = sClass.fields;
        int serverFieldIdx = 0;
        for (int clientFieldIdx = 0; clientFieldIdx < cFields.size(); clientFieldIdx++)
        {
            FieldNode clientField = cFields.get(clientFieldIdx);
            if (serverFieldIdx < sFields.size())
            {
                FieldNode serverField = sFields.get(serverFieldIdx);
                if (!clientField.name.equals(serverField.name))
                {
                    boolean foundServerField = false;
                    for (int k = serverFieldIdx + 1; k < sFields.size(); k++)
                        if (clientField.name.equals(sFields.get(k).name))
                        {
                            foundServerField = true;
                            break;
                        }
                    if (foundServerField)
                    {
                        boolean foundClientField = false;
                        for (int k = clientFieldIdx + 1; k < cFields.size(); k++)
                            if (serverField.name.equals(cFields.get(k).name))
                            {
                                foundClientField = true;
                                break;
                            }
                        if (!foundClientField)
                        {
                            if (serverField.visibleAnnotations == null)
                                serverField.visibleAnnotations = new ArrayList<AnnotationNode>();
                            serverField.visibleAnnotations.add(sideAnn(false));
                            cFields.add(clientFieldIdx, serverField);
                        }
                    }
                    else
                    {
                        if (clientField.visibleAnnotations == null)
                            clientField.visibleAnnotations = new ArrayList<AnnotationNode>();
                        clientField.visibleAnnotations.add(sideAnn(true));
                        sFields.add(serverFieldIdx, clientField);
                    }
                }
            }
            else
            {
                if (clientField.visibleAnnotations == null)
                    clientField.visibleAnnotations = new ArrayList<AnnotationNode>();
                clientField.visibleAnnotations.add(sideAnn(true));
                sFields.add(serverFieldIdx, clientField);
            }
            serverFieldIdx++;
        }
        if (sFields.size() != cFields.size())
        {
            for (int x = cFields.size(); x < sFields.size(); x++)
            {
                FieldNode sF = sFields.get(x);
                if (sF.visibleAnnotations == null)
                    sF.visibleAnnotations = new ArrayList<AnnotationNode>();
                sF.visibleAnnotations.add(sideAnn(true));
                cFields.add(x++, sF);
            }
        }
    }

    static class MW
    {
        MethodNode node;
        boolean client, server;

        MW(MethodNode n)
        {
            this.node = n;
        }

        public boolean equals(Object o)
        {
            if (!(o instanceof MW))
                return false;
            MW mw = (MW) o;
            boolean eq = Objects.equals(node.name, mw.node.name) && Objects.equals(node.desc, mw.node.desc);
            if (eq)
            {
                mw.client = client | mw.client;
                mw.server = server | mw.server;
                client = client | mw.client;
                server = server | mw.server;
            }
            return eq;
        }

        public int hashCode()
        {
            return Objects.hashCode(node.name) * 31 + (node.desc == null ? 0 : node.desc.hashCode());
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void processMethods(ClassNode cClass, ClassNode sClass)
    {
        List<MethodNode> cMethods = cClass.methods;
        List<MethodNode> sMethods = sClass.methods;
        LinkedHashSet<MW> all = new LinkedHashSet<MW>();
        int cPos = 0, sPos = 0, cLen = cMethods.size(), sLen = sMethods.size();
        String clientName = "", lastName = "", serverName = "";
        while (cPos < cLen || sPos < sLen)
        {
            do
            {
                if (sPos >= sLen)
                    break;
                MethodNode sM = sMethods.get(sPos);
                serverName = sM.name;
                if (!serverName.equals(lastName) && cPos != cLen)
                    break;
                MW mw = new MW(sM);
                mw.server = true;
                all.add(mw);
                sPos++;
            } while (sPos < sLen);
            do
            {
                if (cPos >= cLen)
                    break;
                MethodNode cM = cMethods.get(cPos);
                lastName = clientName;
                clientName = cM.name;
                if (!clientName.equals(lastName) && sPos != sLen)
                    break;
                MW mw = new MW(cM);
                mw.client = true;
                all.add(mw);
                cPos++;
            } while (cPos < cLen);
        }
        cMethods.clear();
        sMethods.clear();
        for (MW mw : all)
        {
            cMethods.add(mw.node);
            sMethods.add(mw.node);
            if (!(mw.server && mw.client))
            {
                if (mw.node.visibleAnnotations == null)
                    mw.node.visibleAnnotations = new ArrayList<AnnotationNode>();
                mw.node.visibleAnnotations.add(sideAnn(mw.client));
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static void processInners(ClassNode cClass, ClassNode sClass)
    {
        List<InnerClassNode> ci = cClass.innerClasses;
        List<InnerClassNode> si = sClass.innerClasses;
        for (InnerClassNode n : new ArrayList<InnerClassNode>(ci))
            if (!contains(si, n))
                si.add(n);
        for (InnerClassNode n : new ArrayList<InnerClassNode>(si))
            if (!contains(ci, n))
                ci.add(n);
    }

    static boolean contains(List<InnerClassNode> list, InnerClassNode n)
    {
        for (InnerClassNode m : list)
            if (m.name.equals(n.name))
                return true;
        return false;
    }

    // ---- simple delta: COPY/DATA ops, GDiff format ----

    static void makeDiff(byte[] src, byte[] tgt, OutputStream out) throws IOException
    {
        DataOutputStream d = new DataOutputStream(out);
        GDiffWriter w = new GDiffWriter(d); // writes d1ff d1ff 04 magic itself
        // index of 4-byte hashes -> sample position (keep first)
        HashMap<Integer, Integer> idx = new HashMap<Integer, Integer>();
        for (int i = 0; i + 4 <= src.length; i++)
        {
            int h = hash(src, i);
            if (!idx.containsKey(h))
                idx.put(h, i);
        }
        int i = 0;
        while (i < tgt.length)
        {
            int bestOff = -1, bestLen = 0;
            if (i + 4 <= tgt.length)
            {
                Integer cand = idx.get(hash(tgt, i));
                if (cand != null)
                {
                    int off = cand, len = 0;
                    int max = Math.min(src.length - off, tgt.length - i);
                    while (len < max && src[off + len] == tgt[i + len])
                        len++;
                    if (len >= 6 && len > bestLen)
                    {
                        bestOff = off;
                        bestLen = len;
                    }
                }
            }
            if (bestLen >= 6)
            {
                w.addCopy(bestOff, bestLen);
                i += bestLen;
            }
            else
            {
                w.addData(tgt[i]);
                i++;
            }
        }
        w.flush();
        d.writeByte(0); // EOF
        d.flush();
    }

    static int hash(byte[] b, int i)
    {
        return (int) ((((long) (b[i] & 0xff)) << 24 | ((long) (b[i + 1] & 0xff)) << 16 | ((long) (b[i + 2] & 0xff)) << 8 | (b[i + 3] & 0xff)) * 2654435761L >>> 16);
    }

    static byte[] readAll(InputStream in) throws IOException
    {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0)
            o.write(b, 0, n);
        return o.toByteArray();
    }
}
