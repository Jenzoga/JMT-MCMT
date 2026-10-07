import lzma.streams.LzmaInputStream;
import lzma.sdk.lzma.Decoder;

import java.io.*;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Pack200;
import java.util.zip.ZipFile;

/**
 * Extracts exists=false binpatches (new classes created by Forge patches, e.g.
 * Item$22) from a binpatches.pack.lzma and injects the full class files into
 * userdev classes.jar under their target (SRG) names, so that TaskApplyBinPatches
 * copies them into the binpatched jar and deobfBin maps them to MCP names.
 */
public class InjectNewClasses
{

    static java.util.Map<String, String> mdMap = new java.util.HashMap<String, String>();

    static void loadMd(java.io.File srg) throws Exception
    {
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(srg));
        String line;
        while ((line = br.readLine()) != null)
        {
            if (!line.startsWith("MD:"))
                continue;
            String[] p = line.trim().split("\\s+");
            // MD: owner/name desc owner/new desc
            if (p.length == 5)
            {
                String[] src = p[1].split("/");
                String[] dst = p[3].split("/");
                String owner = src[0];
                String name = src[1];
                mdMap.put(owner + "." + name + " " + p[2], dst[dst.length - 1]);
            }
        }
        br.close();
    }

    static byte[] renameInterfaceMethods(byte[] cls)
    {
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(cls);
        org.objectweb.asm.tree.ClassNode cn = new org.objectweb.asm.tree.ClassNode();
        cr.accept(cn, 0);
        boolean changed = false;
        for (org.objectweb.asm.tree.MethodNode mn : cn.methods)
        {
            String found = null;
            for (String iface : cn.interfaces)
            {
                found = mdMap.get(iface + "." + mn.name + " " + mn.desc);
                if (found != null)
                    break;
            }
            if (found == null)
            {
                found = mdMap.get(cn.superName + "." + mn.name + " " + mn.desc);
            }
            if (found != null && !found.equals(mn.name))
            {
                mn.name = found;
                changed = true;
            }
        }
        if (!changed)
            return cls;
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static void main(String[] args) throws Exception
    {
        File lzma = new File(args[0]);
        String side = args[1];
        File classesJar = new File(args[2]);
        File mergedJar = new File(args[3]);
        File notchMcpSrg = new File(args[4]);
        java.util.Map<String, String> mcpToNotch = new java.util.HashMap<String, String>();
        try (BufferedReader br = new BufferedReader(new FileReader(notchMcpSrg)))
        {
            String line;
            while ((line = br.readLine()) != null)
            {
                if (!line.startsWith("CL:"))
                    continue;
                String[] p = line.trim().split("\\s+");
                if (p.length == 3)
                    mcpToNotch.put(p[2], p[1]);
            }
        }
        final java.util.zip.ZipFile mergedZ = new java.util.zip.ZipFile(mergedJar);
        loadMd(notchMcpSrg);
        System.out.println("mappings=" + mcpToNotch.size() + " methods=" + mdMap.size());

        File tmp = File.createTempFile("newcls", ".jar");
        try (LzmaInputStream in = new LzmaInputStream(new FileInputStream(lzma), new Decoder());
             JarOutputStream out = new JarOutputStream(new FileOutputStream(tmp)))
        {
            Pack200.newUnpacker().unpack(in, out);
        }

        ZipFile patches = new ZipFile(tmp);
        int injected = 0;
        // collect first: name -> bytes
        java.util.Map<String, byte[]> newCls = new java.util.TreeMap<String, byte[]>();
        for (java.util.Enumeration<? extends java.util.zip.ZipEntry> ee = patches.entries(); ee.hasMoreElements();)
        {
            java.util.zip.ZipEntry e = ee.nextElement();
            String en = e.getName();
            if (!en.startsWith("binpatch/" + side + "/") || !en.endsWith(".binpatch"))
                continue;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            try (InputStream is = patches.getInputStream(e))
            {
                while ((r = is.read(b)) > 0)
                    o.write(b, 0, r);
            }
            byte[] all = o.toByteArray();
            DataInputStream d = new DataInputStream(new ByteArrayInputStream(all));
            d.readUTF();
            d.readUTF();
            String tgt = d.readUTF();
            boolean exists = d.readBoolean();
            if (exists)
                continue;
            int len = d.readInt();
            byte[] diff = new byte[len];
            d.readFully(diff);
            byte[] cls = new com.nothome.delta.GDiffPatcher().patch(new byte[0], diff);
            cls = renameInterfaceMethods(cls);
            String asPath = tgt.replace('.', '/') + ".class";
            String notch = mcpToNotch.get(tgt.replace('.', '/'));
            if (notch != null && mergedZ.getEntry(notch.replace('.', '/') + ".class") != null)
                continue; // already present in merged via notch name, deobf maps it
            newCls.put(asPath, cls);
        }
        patches.close();
        tmp.delete();

        File out = new File(classesJar.getPath() + ".tmp");
        int replaced = 0;
        try (ZipFile in = new ZipFile(classesJar);
             JarOutputStream jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(out))))
        {
            java.util.Set<String> done = new java.util.HashSet<String>();
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> ie = in.entries(); ie.hasMoreElements();)
            {
                java.util.zip.ZipEntry e = ie.nextElement();
                jos.putNextEntry(new JarEntry(e.getName()));
                InputStream is = in.getInputStream(e);
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0)
                    jos.write(buf, 0, n);
                is.close();
                jos.closeEntry();
                done.add(e.getName());
            }
            for (java.util.Map.Entry<String, byte[]> en : newCls.entrySet())
            {
                if (done.contains(en.getKey()))
                {
                    replaced++;
                    continue;
                }
                jos.putNextEntry(new JarEntry(en.getKey()));
                jos.write(en.getValue());
                jos.closeEntry();
                injected++;
            }
        }
        if (!out.renameTo(classesJar))
            throw new IOException("rename failed");
        System.out.println("skipped-present=" + (0) + " new classes=" + newCls.size() + " injected=" + injected + " already-present=" + replaced);
    }
}
