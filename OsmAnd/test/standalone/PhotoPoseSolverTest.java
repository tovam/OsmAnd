import net.osmand.util.PhotoPoseSolver;
public class PhotoPoseSolverTest {
    public static void main(String[] args) {
        double[][] points={{-7,1,-20},{-2,2,-23},{8,0,-30},{-4,3,-36},{6,1,-40},{2,4,-27},{-8,2,-33}};
        double[] truth={1,10,2,0.03,-0.28,0.04,Math.log(0.9)};
        double[][] image=new double[points.length][];
        for(int i=0;i<points.length;i++)image[i]=PhotoPoseSolver.project(truth,points[i],1.5);
        double[] initial=truth.clone();initial[0]+=1;initial[1]-=0.4;initial[2]-=0.8;initial[3]+=0.08;
        for(boolean focal:new boolean[]{false,true}) {
            PhotoPoseSolver.Result r=PhotoPoseSolver.solve(points,image,initial,1500,1000,focal);
            if(r.rmsPixels>0.01)throw new AssertionError("RMS "+r.rmsPixels);
            for(int k=0;k<3;k++)if(Math.abs(r.parameters[k]-truth[k])>0.001)throw new AssertionError("Camera position");
        }
        double[] wrongFocal=initial.clone();wrongFocal[6]=Math.log(0.7);
        PhotoPoseSolver.Result five=PhotoPoseSolver.solve(java.util.Arrays.copyOf(points,5),
                java.util.Arrays.copyOf(image,5),wrongFocal,1500,1000,true);
        if(five.rmsPixels>0.02 || Math.abs(five.parameters[6]-truth[6])>0.001) throw new AssertionError("Five-point unknown focal fit");
        for(int i=0;i<image.length;i++){image[i][0]+=((i%3)-1)*0.0003;image[i][1]+=((i%2)-0.5)*0.0005;}
        PhotoPoseSolver.Result noisy=PhotoPoseSolver.solve(points,image,initial,1500,1000,true);
        if(noisy.rmsPixels>1)throw new AssertionError("Noisy fit");
        double[][] line={{0.1,0.1},{0.2,0.2},{0.3,0.3},{0.4,0.4},{0.5,0.5},{0.6,0.6},{0.7,0.7}};
        try { PhotoPoseSolver.solve(points,line,initial,1500,1000,true); throw new AssertionError("Collinear accepted"); }
        catch(IllegalArgumentException expected) { }
        System.out.println("Photo resection: exact, noisy, fixed/free focal and degenerate fixtures passed");
    }
}
