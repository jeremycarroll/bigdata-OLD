package com.bigdata;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * XorShift - provides a pseudo random number generator without synchronization
 * which is used for unit tests in which we do not want to introduce side
 * effects from synchronization by the test harness on the object under test.
 * 
 * @author Brian Goetz and Tim Peierls
 * @author Bryan Thompson
 */
public class XorShift {

    private static final AtomicInteger seq = new AtomicInteger(8862213);

    private int x = -1831433054;

    public XorShift(final int seed) {
        x = seed;
    }

    public XorShift() {
        this((int) System.nanoTime() + seq.getAndAdd(129));
    }

    public int next() {
        x ^= x << 6;
        x ^= x >>> 21;
        x ^= (x << 7);
        return x;
    }

    /** For compatibility with {@link Random#nextInt()}. */
    public int nextInt() {

        return next();
        
    }
    
    /** For compatibility with {@link Random#nextBoolean()}. */
    public boolean nextBoolean() {
        
        final int b = next();

        /*
         * mask a bit and test for non-zero. this uses bit ONE which tends to
         * produce true and false with a uniform distribution as demonstrated by
         * the main() routine.
         */
        
        return (b & 1/* mask */) != 0;
        
    }
    
    /** For compatibility with {@link Random#nextFloat()}. */
    public float nextFloat() {
        
        return Float.intBitsToFloat(next());
        
    }

    /**
     * Utility for looking at various distributions generated by
     * {@link XorShift}.
     * 
     * @param args
     */
    public static void main(final String[] args) {

        final XorShift r = new XorShift();
//        final Random r = new Random();
        
        final int ntrials = 1000;
        
        {
            int ntrue = 0;
            for (int i = 0; i < ntrials; i++) {
                if (r.nextBoolean())
                    ntrue++;
            }
            System.out.println("ntrials=" + ntrials + ", ntrue=" + ntrue);
        }

        {
            /*
             * Generate random values and take their running sum.
             * 
             * Note: This does not check for overflow, but it uses long for the
             * sum and int for the random values.
             */
            long sum = 0;
            final int[] a = new int[ntrials];
            for (int i = 0; i < ntrials; i++) {
                final int n = r.nextInt();
                a[i] = n;
                sum += n;
            }

            // The mean of those random values.
            final double mean = sum / (double) ntrials;

            /*
             * Compute the sum of squares of the difference between the random
             * values and their mean.
             */
            double sse = 0; // sum squared error.
            final double[] diffs = new double[ntrials];
            for (int i = 0; i < ntrials; i++) {
                double d = (mean - (double) a[i]); // difference from mean
                d *= d;// squared
                diffs[i] = d;
                sse += d;
            }
            final double var = sse / ntrials; // variance.
            final double stdev = Math.sqrt(var); // standard deviation.
            System.out.println("ntrials=" + ntrials + ", sum=" + sum + ", sse="
                    + sse + ", var=" + var + ", stdev=" + stdev);
        }

    }

}
