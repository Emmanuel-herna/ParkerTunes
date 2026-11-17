import { existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { nodeResolve } from '@rollup/plugin-node-resolve';
import typescript from '@rollup/plugin-typescript';

const resolveTypeScriptJsImports = {
  name: 'resolve-typescript-js-imports',
  resolveId(source, importer) {
    if (!importer || !source.startsWith('.') || !source.endsWith('.js')) {
      return null;
    }

    const candidate = resolve(dirname(importer), source.slice(0, -3) + '.ts');
    return existsSync(candidate) ? candidate : null;
  },
};

export default {
  input: 'src/am-lyrics.ts',
  output: {
    file: '../app/src/main/assets/lyrics/am-lyrics.js',
    format: 'es',
    sourcemap: false,
  },
  plugins: [
    resolveTypeScriptJsImports,
    nodeResolve({ browser: true }),
    typescript({
      tsconfig: false,
      compilerOptions: {
        target: 'ES2022',
        module: 'ESNext',
        moduleResolution: 'Bundler',
        experimentalDecorators: true,
        useDefineForClassFields: false,
        skipLibCheck: true,
        declaration: false,
        sourceMap: false,
      },
    }),
  ],
};
