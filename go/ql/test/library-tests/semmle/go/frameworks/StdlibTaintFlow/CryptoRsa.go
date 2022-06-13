// Code generated by https://github.com/gagliardetto/codebox. DO NOT EDIT.

package main

import "crypto/rsa"

func TaintStepTest_CryptoRsaDecryptOAEP_B0I0O0(sourceCQL interface{}) interface{} {
	fromByte656 := sourceCQL.([]byte)
	intoByte414, _ := rsa.DecryptOAEP(nil, nil, nil, fromByte656, nil)
	return intoByte414
}

func TaintStepTest_CryptoRsaDecryptPKCS1V15_B0I0O0(sourceCQL interface{}) interface{} {
	fromByte518 := sourceCQL.([]byte)
	intoByte650, _ := rsa.DecryptPKCS1v15(nil, nil, fromByte518)
	return intoByte650
}

func TaintStepTest_CryptoRsaPrivateKeyDecrypt_B0I0O0(sourceCQL interface{}) interface{} {
	fromByte784 := sourceCQL.([]byte)
	var mediumObjCQL rsa.PrivateKey
	intoByte957, _ := mediumObjCQL.Decrypt(nil, fromByte784, nil)
	return intoByte957
}

func RunAllTaints_CryptoRsa() {
	{
		source := newSource(0)
		out := TaintStepTest_CryptoRsaDecryptOAEP_B0I0O0(source)
		sink(0, out)
	}
	{
		source := newSource(1)
		out := TaintStepTest_CryptoRsaDecryptPKCS1V15_B0I0O0(source)
		sink(1, out)
	}
	{
		source := newSource(2)
		out := TaintStepTest_CryptoRsaPrivateKeyDecrypt_B0I0O0(source)
		sink(2, out)
	}
}
