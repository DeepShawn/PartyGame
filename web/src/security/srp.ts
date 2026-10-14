import { sha256 } from '@noble/hashes/sha256';
import { bytesToHex, utf8ToBytes } from '@noble/hashes/utils';
import type { SessionCipher } from './session';

export interface AuthChallenge { type: 'challenge'; roomId: string; sid: string; salt: string; B: string }
export interface ClientProof { type: 'proof'; sid: string; A: string; M1: string }
export interface ServerProof { type: 'verified'; sid: string; M2: string }

interface ClientSession {
  step1(identity: string, password: string): void;
  step2(salt: string, B: string): { A: string; M1: string };
  step3(M2: string): boolean;
  getSessionKey(): string;
  generateVerifier(salt: string, identity: string, password: string): string;
  generateRandomSalt(): string;
}

interface ServerSession {
  step1(identity: string, salt: string, verifier: string): string;
  step2(A: string, M1: string): string;
  getSessionKey(): string;
}

interface SessionFactory {
  new (): ClientSession;
  prototype: ClientSession;
}

interface ServerFactory {
  new (): ServerSession;
  prototype: ServerSession;
}

function installCryptoProvider(): void {
  const target = globalThis as unknown as { nodeCrypto?: unknown };
  if (target.nodeCrypto) return;
  target.nodeCrypto = {
    createHash: (name: string) => {
      const normalized = String(name).toLowerCase().replace(/[^a-z0-9]/g, '');
      if (normalized !== 'sha256') throw new Error(`unsupported hash: ${name}`);
      return {
        update: (message: string | Uint8Array) => ({
          digest: () => bytesToHex(sha256(typeof message === 'string' ? utf8ToBytes(message) : message)),
        }),
      };
    },
  };
}

function randomHex(bytes: number): string {
  const buffer = new Uint8Array(bytes);
  crypto.getRandomValues(buffer);
  return bytesToHex(buffer);
}

export const SRP_N = '21766174458617435773191008891802753781907668374255538511144643224689886235383840957210909013086056401571399717235807266581649606472148410291413364152197364477180887395655483738115072677402235101762521901569820740293149529620419333266262073471054548368736039519702486226506248861060256971802984953561121442680157668000761429988222457090413873973970171927093992114751765168063614761119615476233422096442783117971236371647333871414335895773474667308967050807005509320424799678417036867928316761272274230314067548291133582479583061439577559347101961771406173684378522703483495337037655006751328447510550299250924469288819';
export const SRP_G = '2';
export const SRP_K = '5b9e8ef059c6b32ea59fc1d322d37f04aa30bae5aa9003b8321e21ddb04e300';

let factories: { client: SessionFactory; server: ServerFactory } | null = null;

async function loadFactories(): Promise<{ client: SessionFactory; server: ServerFactory }> {
  if (factories) return factories;
  installCryptoProvider();
  // 使用 security/vendor 下的 thinbus-srp 2.0.2 本地副本：原包在 Node 探测分支里有静态
  // require('crypto')，浏览器打包器无法解析；副本仅替换该分支为 Web Crypto，算法逐字节一致。
  const clientModule = (await import('./vendor/thinbus-client.mjs')) as { default: (n: string, g: string, k: string) => SessionFactory };
  const serverModule = (await import('./vendor/thinbus-server.mjs')) as { default: (n: string, g: string, k: string) => ServerFactory };
  factories = {
    client: clientModule.default(SRP_N, SRP_G, SRP_K),
    server: serverModule.default(SRP_N, SRP_G, SRP_K),
  };
  return factories;
}

export class SrpClient {
  private constructor(
    private readonly roomId: string,
    private readonly session: ClientSession,
  ) {}

  static async create(roomId: string, password: string): Promise<SrpClient> {
    const { client } = await loadFactories();
    const session = new client();
    session.step1(roomId, password);
    return new SrpClient(roomId, session);
  }

  private sid: string | null = null;
  private finished = false;

  answer(challenge: AuthChallenge): ClientProof {
    if (this.sid) throw new Error('认证会话已使用');
    if (challenge.type !== 'challenge' || challenge.roomId !== this.roomId) throw new Error('房间不匹配');
    if (!/^[0-9a-f]{1,64}$/.test(challenge.sid) || !/^[0-9a-f]{1,64}$/.test(challenge.salt) || !/^[0-9a-f]{1,512}$/.test(challenge.B)) {
      throw new Error('认证数据格式无效');
    }
    this.sid = challenge.sid;
    const credentials = this.session.step2(challenge.salt, challenge.B);
    return { type: 'proof', sid: challenge.sid, A: credentials.A, M1: credentials.M1 };
  }

  async finish(proof: ServerProof, makeCipher: (roomId: string, sid: string, key: string, host: boolean) => Promise<SessionCipher>): Promise<SessionCipher> {
    if (this.finished || !this.sid) throw new Error('认证顺序错误');
    this.finished = true;
    if (proof.type !== 'verified' || proof.sid !== this.sid) throw new Error('认证证明无效');
    this.session.step3(proof.M2);
    const key = this.session.getSessionKey();
    return makeCipher(this.roomId, this.sid, key, false);
  }
}

export interface SrpHostChallenge {
  challenge: AuthChallenge;
  verify(
    proof: ClientProof,
    makeCipher: (roomId: string, sid: string, key: string, host: boolean) => Promise<SessionCipher>,
  ): Promise<{ proof: ServerProof; cipher: SessionCipher }>;
}

export async function createSrpHost(roomId: string, password: string): Promise<SrpHostChallenge> {
  const { client, server } = await loadFactories();
  const salt = randomHex(32);
  const verifier = new client().generateVerifier(salt, roomId, password);
  const session = new server();
  const sid = randomHex(32);
  const B = session.step1(roomId, salt, verifier);
  let used = false;
  return {
    challenge: { type: 'challenge', roomId, sid, salt, B },
    async verify(proof, makeCipher) {
      if (used) throw new Error('认证已过期');
      used = true;
      if (proof.type !== 'proof' || proof.sid !== sid) throw new Error('认证会话无效');
      if (!/^[0-9a-f]{1,512}$/.test(proof.A) || !/^[0-9a-f]{1,64}$/.test(proof.M1)) throw new Error('认证数据无效');
      const m2 = session.step2(proof.A, proof.M1);
      const trimmed = String(m2).replace(/^0+/, '') || '0';
      const key = session.getSessionKey();
      const cipher = await makeCipher(roomId, sid, key, true);
      return { proof: { type: 'verified', sid, M2: trimmed } as ServerProof, cipher };
    },
  };
}
