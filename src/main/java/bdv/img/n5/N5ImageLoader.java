/*
 * #%L
 * BigDataViewer core classes with minimal dependencies.
 * %%
 * Copyright (C) 2012 - 2021 BigDataViewer developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bdv.img.n5;

import bdv.AbstractViewerSetupImgLoader;
import bdv.ViewerImgLoader;
import bdv.cache.CacheControl;
import bdv.img.cache.SimpleCacheArrayLoader;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.util.ConstantRandomAccessible;
import bdv.util.MipmapTransforms;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.IntFunction;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.queue.BlockingFetchQueues;
import net.imglib2.cache.queue.FetcherThreads;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileByteArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileDoubleArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileFloatArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileIntArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileLongArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.cell.CellImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileByteType;
import net.imglib2.type.volatiles.VolatileDoubleType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.type.volatiles.VolatileIntType;
import net.imglib2.type.volatiles.VolatileLongType;
import net.imglib2.type.volatiles.VolatileShortType;
import net.imglib2.type.volatiles.VolatileUnsignedByteType;
import net.imglib2.type.volatiles.VolatileUnsignedIntType;
import net.imglib2.type.volatiles.VolatileUnsignedLongType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import static bdv.img.n5.BdvN5Format.DATA_TYPE_KEY;
import static bdv.img.n5.BdvN5Format.DOWNSAMPLING_FACTORS_KEY;
import static bdv.img.n5.BdvN5Format.getPathName;

public class N5ImageLoader implements ViewerImgLoader, MultiResolutionImgLoader
{
	private final File n5File;

	// TODO: it would be good if this would not be needed
	//       find available setups from the n5
	private final AbstractSequenceDescription< ?, ?, ? > seq;

	/**
	 * Maps setup id to {@link SetupImgLoader}.
	 */
	private final Map< Integer, SetupImgLoader > setupImgLoaders = new HashMap<>();

	public N5ImageLoader( final File n5File, final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		this.n5File = n5File;
		this.seq = sequenceDescription;
	}

	public File getN5File()
	{
		return n5File;
	}

	private volatile boolean isOpen = false;
	private FetcherThreads fetchers;
	private VolatileGlobalCellCache cache;
	private N5Reader n5;

	private void open()
	{
		if ( !isOpen )
		{
			synchronized ( this )
			{
				if ( isOpen )
					return;

				try
				{
					this.n5 = new N5FSReader( n5File.getAbsolutePath() );

					int maxNumLevels = 0;
					final List< ? extends BasicViewSetup > setups = seq.getViewSetupsOrdered();
					for ( final BasicViewSetup setup : setups )
					{
						final int setupId = setup.getId();
						final SetupImgLoader setupImgLoader = createSetupImgLoader( setupId );
						setupImgLoaders.put( setupId, setupImgLoader );
						maxNumLevels = Math.max( maxNumLevels, setupImgLoader.numMipmapLevels() );
					}

					final int numFetcherThreads = Math.max( 1, Runtime.getRuntime().availableProcessors() );
					final BlockingFetchQueues< Callable< ? > > queue = new BlockingFetchQueues<>( maxNumLevels, numFetcherThreads );
					fetchers = new FetcherThreads( queue, numFetcherThreads );
					cache = new VolatileGlobalCellCache( queue );
				}
				catch ( IOException e )
				{
					throw new RuntimeException( e );
				}

				isOpen = true;
			}
		}
	}

	/**
	 * Clear the cache. Images that were obtained from
	 * this loader before {@link #close()} will stop working. Requesting images
	 * after {@link #close()} will cause the n5 to be reopened (with a
	 * new cache).
	 */
	public void close()
	{
		if ( isOpen )
		{
			synchronized ( this )
			{
				if ( !isOpen )
					return;
				fetchers.shutdown();
				cache.clearCache();
				isOpen = false;
			}
		}
	}

	@Override
	public SetupImgLoader getSetupImgLoader( final int setupId )
	{
		open();
		return setupImgLoaders.get( setupId );
	}

	private < T extends NativeType< T >, V extends Volatile< T > & NativeType< V > > SetupImgLoader< T, V > createSetupImgLoader( final int setupId ) throws IOException
	{
		final String pathName = getPathName( setupId );
		final DataType dataType = n5.getAttribute( pathName, DATA_TYPE_KEY, DataType.class );
		switch ( dataType )
		{
		case UINT8:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new UnsignedByteType(), new VolatileUnsignedByteType() ) );
		case UINT16:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new UnsignedShortType(), new VolatileUnsignedShortType() ) );
		case UINT32:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new UnsignedIntType(), new VolatileUnsignedIntType() ) );
		case UINT64:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new UnsignedLongType(), new VolatileUnsignedLongType() ) );
		case INT8:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new ByteType(), new VolatileByteType() ) );
		case INT16:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new ShortType(), new VolatileShortType() ) );
		case INT32:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new IntType(), new VolatileIntType() ) );
		case INT64:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new LongType(), new VolatileLongType() ) );
		case FLOAT32:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new FloatType(), new VolatileFloatType() ) );
		case FLOAT64:
			return Cast.unchecked( new SetupImgLoader<>( setupId, new DoubleType(), new VolatileDoubleType() ) );
		}
		return null;
	}

	@Override
	public CacheControl getCacheControl()
	{
		open();
		return cache;
	}

	public class SetupImgLoader< T extends NativeType< T >, V extends Volatile< T > & NativeType< V > >
			extends AbstractViewerSetupImgLoader< T, V >
			implements MultiResolutionSetupImgLoader< T >
	{
		private final int setupId;

		private final double[][] mipmapResolutions;

		private final AffineTransform3D[] mipmapTransforms;

		public SetupImgLoader( final int setupId, final T type, final V volatileType ) throws IOException
		{
			super( type, volatileType );
			this.setupId = setupId;
			final String pathName = getPathName( setupId );
			mipmapResolutions = n5.getAttribute( pathName, DOWNSAMPLING_FACTORS_KEY, double[][].class );
			mipmapTransforms = new AffineTransform3D[ mipmapResolutions.length ];
			for ( int level = 0; level < mipmapResolutions.length; level++ )
				mipmapTransforms[ level ] = MipmapTransforms.getMipmapTransformDefault( mipmapResolutions[ level ] );
		}

		@Override
		public RandomAccessibleInterval< V > getVolatileImage( final int timepointId, final int level, final ImgLoaderHint... hints )
		{
			return prepareCachedImage( timepointId, level, LoadingStrategy.BUDGETED, volatileType );
		}

		@Override
		public RandomAccessibleInterval< T > getImage( final int timepointId, final int level, final ImgLoaderHint... hints )
		{
			return prepareCachedImage( timepointId, level, LoadingStrategy.BLOCKING, type );
		}

		@Override
		public Dimensions getImageSize( final int timepointId, final int level )
		{
			try
			{
				final String pathName = getPathName( setupId, timepointId, level );
				final DatasetAttributes attributes = n5.getDatasetAttributes( pathName );
				return new FinalDimensions( attributes.getDimensions() );
			}
			catch( Exception e )
			{
				return null;
			}
		}

		@Override
		public double[][] getMipmapResolutions()
		{
			return mipmapResolutions;
		}

		@Override
		public AffineTransform3D[] getMipmapTransforms()
		{
			return mipmapTransforms;
		}

		@Override
		public int numMipmapLevels()
		{
			return mipmapResolutions.length;
		}

		@Override
		public VoxelDimensions getVoxelSize( final int timepointId )
		{
			return null;
		}

		/**
		 * Create a {@link CellImg} backed by the cache.
		 */
		private < T extends NativeType< T > > RandomAccessibleInterval< T > prepareCachedImage( final int timepointId, final int level, final LoadingStrategy loadingStrategy, final T type )
		{
			try
			{
				final String pathName = getPathName( setupId, timepointId, level );
				final DatasetAttributes attributes = n5.getDatasetAttributes( pathName );
				final long[] dimensions = attributes.getDimensions();
				final int[] cellDimensions = attributes.getBlockSize();
				final CellGrid grid = new CellGrid( dimensions, cellDimensions );

				final int priority = numMipmapLevels() - 1 - level;
				final CacheHints cacheHints = new CacheHints( loadingStrategy, priority, false );

				final SimpleCacheArrayLoader< ? > loader = createCacheArrayLoader( n5, pathName );
				return cache.createImg( grid, timepointId, setupId, level, cacheHints, loader, type );
			}
			catch ( IOException e )
			{
				System.err.println( String.format(
						"image data for timepoint %d setup %d level %d could not be found.",
						timepointId, setupId, level ) );
				return Views.interval(
						new ConstantRandomAccessible<>( type.createVariable(), 3 ),
						new FinalInterval( 1, 1, 1 ) );
			}
		}
	}

	private static class N5CacheArrayLoader< T, A > implements SimpleCacheArrayLoader< A >
	{
		private final N5Reader n5;
		private final String pathName;
		private final DatasetAttributes attributes;
		private final IntFunction< T > createPrimitiveArray;
		private final Function< T, A > createVolatileArrayAccess;

		N5CacheArrayLoader( final N5Reader n5, final String pathName, final DatasetAttributes attributes,
				final IntFunction< T > createPrimitiveArray,
				final Function< T, A > createVolatileArrayAccess )
		{
			this.n5 = n5;
			this.pathName = pathName;
			this.attributes = attributes;
			this.createPrimitiveArray = createPrimitiveArray;
			this.createVolatileArrayAccess = createVolatileArrayAccess;
		}

		@Override
		public A loadArray( final long[] gridPosition, final int[] cellDimensions ) throws IOException
		{
			final DataBlock< T > dataBlock = Cast.unchecked( n5.readBlock( pathName, attributes, gridPosition ) );
			if ( dataBlock != null && Arrays.equals( dataBlock.getSize(), cellDimensions ) )
			{
				return createVolatileArrayAccess.apply( dataBlock.getData() );
			}
			else
			{
				final T data = createPrimitiveArray.apply( ( int ) Intervals.numElements( cellDimensions ) );
				if ( dataBlock != null )
				{
					final T src = dataBlock.getData();
					final int[] srcDims = dataBlock.getSize();
					final int[] pos = new int[ srcDims.length ];
					final int[] size = new int[ srcDims.length ];
					Arrays.setAll( size, d -> Math.min( srcDims[ d ], cellDimensions[ d ] ) );
					ndArrayCopy( src, srcDims, pos, data, cellDimensions, pos, size );
				}
				return createVolatileArrayAccess.apply( data );
			}
		}
	}

	public static SimpleCacheArrayLoader< ? > createCacheArrayLoader( final N5Reader n5, final String pathName ) throws IOException
	{
		final DatasetAttributes attributes = n5.getDatasetAttributes( pathName );
		switch ( attributes.getDataType() )
		{
		case UINT8:
		case INT8:
			return new N5CacheArrayLoader<>( n5, pathName, attributes, byte[]::new, data -> new VolatileByteArray( data, true ) );
		case UINT16:
		case INT16:
			return new N5CacheArrayLoader<>( n5, pathName, attributes, short[]::new, data -> new VolatileShortArray( data, true ) );
		case UINT32:
		case INT32:
			return new N5CacheArrayLoader<>( n5, pathName, attributes, int[]::new, data -> new VolatileIntArray( data, true ) );
		case UINT64:
		case INT64:
			return new N5CacheArrayLoader<>( n5, pathName, attributes, long[]::new, data -> new VolatileLongArray( data, true ) );
		case FLOAT32:
			return new N5CacheArrayLoader<>( n5, pathName, attributes, float[]::new, data -> new VolatileFloatArray( data, true ) );
		case FLOAT64:
			return new N5CacheArrayLoader<>( n5, pathName, attributes, double[]::new, data -> new VolatileDoubleArray( data, true ) );
		default:
			throw new IllegalArgumentException();
		}
	}

	/**
	 * Like `System.arrayCopy()` but for flattened nD arrays.
	 *
	 * @param src
	 * 		the (flattened) source array.
	 * @param srcSize
	 * 		dimensions of the source array.
	 * @param srcPos
	 * 		starting position in the source array.
	 * @param dest
	 * 		the (flattened destination array.
	 * @param destSize
	 * 		dimensions of the source array.
	 * @param destPos
	 * 		starting position in the destination data.
	 * @param size
	 * 		the number of array elements to be copied.
	 */
	// TODO: This will be moved to a new imglib2-blk artifact later. Re-use it from there when that happens.
	private static < T > void ndArrayCopy(
			final T src,  final int[] srcSize,  final int[] srcPos,
			final T dest, final int[] destSize, final int[] destPos,
			final int[] size)
	{
		final int n = srcSize.length;
		int srcStride = 1;
		int destStride = 1;
		int srcOffset = 0;
		int destOffset = 0;
		for ( int d = 0; d < n; ++d )
		{
			srcOffset += srcStride * srcPos[ d ];
			srcStride *= srcSize[ d ];
			destOffset += destStride * destPos[ d ];
			destStride *= destSize[ d ];
		}
		ndArrayCopy( n - 1, src, srcSize, srcOffset, dest, destSize, destOffset, size );
	}

	private static <T> void ndArrayCopy(
			final int d,
			final T src,  final int[] srcSize,  final int srcPos,
			final T dest, final int[] destSize, final int destPos,
			final int[] size)
	{
		if ( d == 0 )
			System.arraycopy( src, srcPos, dest, destPos, size[ d ] );
		else
		{
			int srcStride = 1;
			int destStride = 1;
			for ( int dd = 0; dd < d; ++dd )
			{
				srcStride *= srcSize[ dd ];
				destStride *= destSize[ dd ];
			}

			final int w = size[ d ];
			for ( int x = 0; x < w; ++x )
			{
				ndArrayCopy( d - 1,
						src, srcSize, srcPos + x * srcStride,
						dest, destSize, destPos + x * destStride,
						size );
			}
		}
	}



}
